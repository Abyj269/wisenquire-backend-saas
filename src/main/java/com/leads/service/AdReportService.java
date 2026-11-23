package com.leads.service;

import com.leads.dto.reports.AdPerformanceReportDto;
import com.leads.dto.reports.AdPerformanceRowDto;
import com.leads.dto.reports.CampaignPerformanceRowDto;
import com.leads.model.entity.Ad;
import com.leads.model.entity.Lead;
import com.leads.model.enums.StageType;
import com.leads.repository.AdInsightRepository;
import com.leads.repository.AdRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Real ad-performance report, replacing the "WhatsApp Campaigns" mock. Rows
 * are seeded from {@link Ad}/{@code ad_insights} first — not from leads —
 * so a Google campaign with zero attributed leads (v1's known attribution
 * gap, see the Ads module plan) still shows its real spend instead of being
 * omitted entirely. Leads are then layered on top wherever {@link
 * Lead#getAd()} resolves.
 * <p>
 * Cohorting follows the exact convention every other report in this class
 * family already uses (see {@code ReportsService.getLeadSourceRoiReport}):
 * {@code leadsCount}/{@code convertedCount}/{@code revenue} reflect leads
 * whose {@code createdAt} falls in {@code [fromDate, toDate]} (via {@link
 * LeadService#findLeadsForReport}), with conversion checked as a
 * current-state flag with no date bound of its own — NOT bounded by {@code
 * convertedAt}. {@code spend} is summed over {@code ad_insights.stat_date}
 * in the same window, independent of which specific leads' clicks landed in
 * it. costPerLead/roas are therefore period-over-period ratios (spend
 * incurred in the window ÷ leads/revenue acquired in the window), not a
 * strict same-lead financial reconciliation.
 */
@Service
@RequiredArgsConstructor
public class AdReportService {
    private final TenantAccess access;

    private final LeadService leadService;
    private final AdRepository adRepo;
    private final AdInsightRepository adInsightRepo;

    private record SpendInfo(BigDecimal spend, String currency) {
    }
    private record CampaignKey(Long connectionId, String campaignId, String currency) {}

    @Transactional(readOnly = true)
    public AdPerformanceReportDto getAdPerformanceReport(Long companyId, Long branchId, LocalDate fromDate, LocalDate toDate) {
        access.scope(companyId, branchId);
        com.leads.util.InputRules.dateRange(fromDate, toDate);
        if (branchId == null) access.companyWide(companyId);
        List<Ad> ads = adRepo.findForReport(companyId, branchId);

        Map<Long, SpendInfo> spendByAdId = adInsightRepo.sumSpendByAd(companyId, branchId, fromDate, toDate).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> new SpendInfo((BigDecimal) row[1], (String) row[2]),
                        (first, second) -> { throw new IllegalArgumentException("An ad has multiple spend currencies in this period. Narrow the date range or correct its imported currency data."); }));

        List<Lead> leads = leadService.findLeadsForReport(companyId, branchId, fromDate, toDate);
        Map<Long, List<Lead>> leadsByAdId = leads.stream()
                .filter(l -> l.getAd() != null)
                .collect(Collectors.groupingBy(l -> l.getAd().getId()));

        List<AdPerformanceRowDto> byAd = new ArrayList<>();
        for (Ad ad : ads) {
            List<Lead> adLeads = leadsByAdId.getOrDefault(ad.getId(), List.of());
            SpendInfo spendInfo = spendByAdId.getOrDefault(ad.getId(), new SpendInfo(BigDecimal.ZERO, null));
            byAd.add(buildAdRow(ad, spendInfo, adLeads, branchId));
        }
        byAd.sort(Comparator.comparing(AdPerformanceRowDto::getSpend).reversed());

        Map<CampaignKey, List<Ad>> adsByCampaign = ads.stream()
                .collect(Collectors.groupingBy(a -> new CampaignKey(a.getConnection().getId(), a.getExternalCampaignId(),
                        spendByAdId.getOrDefault(a.getId(), new SpendInfo(BigDecimal.ZERO, null)).currency())));
        List<CampaignPerformanceRowDto> byCampaign = new ArrayList<>();
        for (Map.Entry<CampaignKey, List<Ad>> entry : adsByCampaign.entrySet()) {
            byCampaign.add(buildCampaignRow(entry.getKey().campaignId(), entry.getValue(), spendByAdId, leadsByAdId, branchId));
        }
        byCampaign.sort(Comparator.comparing(CampaignPerformanceRowDto::getSpend).reversed());

        BigDecimal totalSpend = byAd.stream().map(AdPerformanceRowDto::getSpend).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalLeads = byAd.stream().mapToLong(AdPerformanceRowDto::getLeadsCount).sum();
        long totalConverted = byAd.stream().mapToLong(AdPerformanceRowDto::getConvertedCount).sum();
        BigDecimal totalRevenue = byAd.stream().map(AdPerformanceRowDto::getRevenue).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean mixedCurrency = byAd.stream().map(AdPerformanceRowDto::getCurrency).filter(Objects::nonNull).distinct().count() > 1;
        if (mixedCurrency) totalSpend = null;
        if (mixedCurrency || byAd.stream().anyMatch(r -> r.getRevenue() == null)) totalRevenue = null;
        boolean anyShared = byAd.stream().anyMatch(r -> !r.isBranchExclusive());

        return AdPerformanceReportDto.builder()
                .byAd(byAd)
                .byCampaign(byCampaign)
                .totalSpend(totalSpend)
                .totalLeads(totalLeads)
                .totalConverted(totalConverted)
                .totalRevenue(totalRevenue)
                .anySharedAccountSpend(branchId != null && anyShared)
                .build();
    }

    private AdPerformanceRowDto buildAdRow(Ad ad, SpendInfo spendInfo, List<Lead> leads, Long branchIdFilter) {
        long count = leads.size();
        long converted = leads.stream().filter(this::isConverted).count();
        BigDecimal revenue = sumDealValue(leads.stream().filter(this::isConverted));
        if (leads.stream().filter(this::isConverted).anyMatch(l -> !Objects.equals(LeadService.resolveDefaultCurrency(l), spendInfo.currency()))) revenue = null;
        BigDecimal spend = spendInfo.spend();
        return AdPerformanceRowDto.builder()
                .adId(ad.getId())
                .provider(ad.getProvider())
                .adName(ad.getAdName())
                .campaignName(ad.getCampaignName())
                .status(ad.getStatus())
                .spend(spend)
                .currency(spendInfo.currency())
                .leadsCount(count)
                .convertedCount(converted)
                .conversionRate(count > 0 ? round1((double) converted / count * 100.0) : 0.0)
                .costPerLead(count > 0 && spend.signum() > 0 ? spend.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : null)
                .revenue(revenue)
                .roas(spend.signum() > 0 && revenue != null ? revenue.divide(spend, 4, RoundingMode.HALF_UP).doubleValue() : null)
                .branchExclusive(branchIdFilter == null || ad.getBranch() != null)
                .build();
    }

    private CampaignPerformanceRowDto buildCampaignRow(String externalCampaignId, List<Ad> campaignAds,
                                                        Map<Long, SpendInfo> spendByAdId, Map<Long, List<Lead>> leadsByAdId, Long branchIdFilter) {
        BigDecimal spend = campaignAds.stream()
                .map(a -> spendByAdId.getOrDefault(a.getId(), new SpendInfo(BigDecimal.ZERO, null)).spend())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = campaignAds.stream()
                .map(a -> spendByAdId.get(a.getId()))
                .filter(Objects::nonNull)
                .map(SpendInfo::currency)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        List<Lead> campaignLeads = campaignAds.stream()
                .flatMap(a -> leadsByAdId.getOrDefault(a.getId(), List.of()).stream())
                .collect(Collectors.toList());
        long count = campaignLeads.size();
        long converted = campaignLeads.stream().filter(this::isConverted).count();
        BigDecimal revenue = sumDealValue(campaignLeads.stream().filter(this::isConverted));
        if (campaignLeads.stream().filter(this::isConverted).anyMatch(l -> !Objects.equals(LeadService.resolveDefaultCurrency(l), currency))) revenue = null;
        Ad first = campaignAds.get(0);
        boolean branchExclusive = branchIdFilter == null || campaignAds.stream().allMatch(a -> a.getBranch() != null);

        return CampaignPerformanceRowDto.builder()
                .externalCampaignId(externalCampaignId)
                .provider(first.getProvider())
                .campaignName(first.getCampaignName())
                .spend(spend)
                .currency(currency)
                .leadsCount(count)
                .convertedCount(converted)
                .conversionRate(count > 0 ? round1((double) converted / count * 100.0) : 0.0)
                .costPerLead(count > 0 && spend.signum() > 0 ? spend.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : null)
                .revenue(revenue)
                .roas(spend.signum() > 0 && revenue != null ? revenue.divide(spend, 4, RoundingMode.HALF_UP).doubleValue() : null)
                .branchExclusive(branchExclusive)
                .build();
    }

    // Same definition as ReportsService.isConverted — kept as its own small
    // copy rather than exposing ReportsService's private helper, since
    // duplicating two lines is cheaper than widening that class's API just
    // for this.
    private boolean isConverted(Lead l) {
        return Boolean.TRUE.equals(l.getIsConverted()) || l.getStatus().getStageType() == StageType.CONVERTED;
    }

    private BigDecimal sumDealValue(java.util.stream.Stream<Lead> leads) {
        return leads.map(Lead::getEstimatedDealValue).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
