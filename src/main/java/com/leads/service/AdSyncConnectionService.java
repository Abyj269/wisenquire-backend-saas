package com.leads.service;

import com.leads.dto.ads.ProviderAdDto;
import com.leads.dto.ads.ProviderInsightDto;
import com.leads.model.entity.*;
import com.leads.model.enums.AdStatus;
import com.leads.model.enums.AdSyncStatus;
import com.leads.repository.AdAccountConnectionRepository;
import com.leads.repository.AdInsightRepository;
import com.leads.repository.AdRepository;
import com.leads.repository.LeadRepository;
import com.leads.repository.WhatsAppConversationRepository;
import com.leads.util.AdAttributionResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs one {@link AdAccountConnection}: pulls its current ads/campaigns,
 * pulls daily spend for a date window, upserts both, then resolves any
 * WhatsApp leads still waiting on an Ad link (see {@link
 * AdAttributionResolver}). Deliberately does NOT catch its own exceptions —
 * {@link AdSyncService} (the scheduled orchestrator) does that, in a
 * separate transaction, so a failure here rolls back cleanly instead of
 * leaving a half-updated sync, and {@link #markFailed} can still persist
 * the failure status afterward.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdSyncConnectionService {

    // Meta's Ads Insights API supports lookback to this far; used as-is for
    // Google too — its client just returns whatever data actually exists in
    // range, so a slightly generous window costs nothing.
    private static final int BACKFILL_MONTHS = 37;
    private static final int TRAILING_SYNC_DAYS = 3;

    private final AdAccountConnectionRepository connectionRepo;
    private final AdRepository adRepo;
    private final AdInsightRepository insightRepo;
    private final LeadRepository leadRepo;
    private final WhatsAppConversationRepository conversationRepo;
    private final AdsProviderClientFactory providerClientFactory;

    @Transactional
    public void syncConnection(AdAccountConnection connection) {
        LocalDate today = LocalDate.now();
        doSync(connection, today.minusDays(TRAILING_SYNC_DAYS), today, true);
    }

    @Transactional
    public void backfill(AdAccountConnection connection) {
        LocalDate today = LocalDate.now();
        doSync(connection, today.minusMonths(BACKFILL_MONTHS), today, false);
    }

    private void doSync(AdAccountConnection connection, LocalDate fromDate, LocalDate toDate, boolean activeOrPausedOnly) {
        AdsProviderClient client = providerClientFactory.resolve(connection.getProvider());

        List<ProviderAdDto> providerAds = client.fetchAds(connection);
        Map<String, Ad> adsByExternalId = upsertAds(connection, providerAds);

        List<ProviderInsightDto> insights = client.fetchInsights(connection, fromDate, toDate, activeOrPausedOnly);
        upsertInsights(connection, adsByExternalId, insights);

        reconcileLeads(connection.getCompany(), adsByExternalId.keySet());

        connection.setLastSyncAt(LocalDateTime.now());
        connection.setLastSyncStatus(AdSyncStatus.SUCCESS);
        connection.setLastSyncError(null);
        connectionRepo.save(connection);
    }

    /** Called by AdSyncService after catching an exception from {@link #syncConnection}/{@link #backfill} — a fresh transaction so the failure status survives the rolled-back sync. */
    @Transactional
    public void markFailed(Long connectionId, String errorMessage) {
        connectionRepo.findById(connectionId).ifPresent(connection -> {
            connection.setLastSyncAt(LocalDateTime.now());
            connection.setLastSyncStatus(AdSyncStatus.FAILED);
            connection.setLastSyncError(errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage);
            connectionRepo.save(connection);
        });
    }

    private Map<String, Ad> upsertAds(AdAccountConnection connection, List<ProviderAdDto> providerAds) {
        Map<String, Ad> byExternalId = new HashMap<>();
        for (ProviderAdDto dto : providerAds) {
            Ad ad = adRepo.findByConnectionIdAndExternalAdId(connection.getId(), dto.getExternalAdId())
                    .orElseGet(() -> Ad.builder()
                            .company(connection.getCompany())
                            .connection(connection)
                            .provider(connection.getProvider())
                            .externalAdId(dto.getExternalAdId())
                            .build());
            ad.setBranch(connection.getBranch());
            ad.setExternalCampaignId(dto.getExternalCampaignId());
            ad.setExternalAdsetId(dto.getExternalAdsetId());
            ad.setAdName(dto.getAdName());
            ad.setCampaignName(dto.getCampaignName());
            ad.setAdsetName(dto.getAdsetName());
            ad.setObjective(dto.getObjective());
            ad.setStatus(connection.getProvider() == com.leads.model.enums.AdProvider.META
                    ? AdStatus.fromMeta(dto.getStatus())
                    : AdStatus.fromGoogle(dto.getStatus()));
            byExternalId.put(dto.getExternalAdId(), adRepo.save(ad));
        }
        return byExternalId;
    }

    private void upsertInsights(AdAccountConnection connection, Map<String, Ad> adsByExternalId, List<ProviderInsightDto> insights) {
        for (ProviderInsightDto dto : insights) {
            Ad ad = adsByExternalId.get(dto.getExternalAdId());
            if (ad == null) {
                // The insights call can, in principle, mention an ad the ads
                // call didn't return in the same run (pagination edge case,
                // or the ad was deleted between the two calls) — skip rather
                // than fail the whole sync over one orphaned spend row.
                log.warn("Skipping insight row for unknown ad {} on connection {}", dto.getExternalAdId(), connection.getId());
                continue;
            }
            AdInsight insight = insightRepo.findByAdIdAndStatDate(ad.getId(), dto.getStatDate())
                    .orElseGet(() -> AdInsight.builder().ad(ad).statDate(dto.getStatDate()).build());
            insight.setSpend(dto.getSpend());
            insight.setCurrency(dto.getCurrency());
            insight.setImpressions(dto.getImpressions());
            insight.setClicks(dto.getClicks());
            insightRepo.save(insight);
        }
    }

    private void reconcileLeads(Company company, java.util.Set<String> justSyncedExternalAdIds) {
        if (justSyncedExternalAdIds.isEmpty()) return;
        List<String> ids = List.copyOf(justSyncedExternalAdIds);
        List<WhatsAppConversation> pending = conversationRepo.findPendingAdAttribution(company.getId(), ids);
        for (WhatsAppConversation conversation : pending) {
            Lead lead = conversation.getLead();
            Long leadBranchId = lead.getBranch() != null ? lead.getBranch().getId() : null;
            List<Ad> candidates = adRepo.findByCompanyIdAndExternalAdId(company.getId(), conversation.getAdSourceId());
            Ad resolved = AdAttributionResolver.pickBestMatch(candidates, leadBranchId);
            if (resolved != null) {
                lead.setAd(resolved);
                leadRepo.save(lead);
            }
        }
    }
}
