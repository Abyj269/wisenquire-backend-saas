package com.leads.service;

import com.leads.dto.ads.AdAccountConnectionDto;
import com.leads.dto.ads.AdAccountConnectionUpsertRequest;
import com.leads.dto.ads.AdConnectionTestResult;
import com.leads.model.entity.AdAccountConnection;
import com.leads.model.entity.Branch;
import com.leads.model.entity.Company;
import com.leads.model.enums.AdProvider;
import com.leads.repository.AdAccountConnectionRepository;
import com.leads.repository.BranchRepository;
import com.leads.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CRUD for connected Meta/Google ad accounts — mirrors {@link
 * WhatsAppConfigService}'s shape (masked-on-read secrets, "blank on update
 * keeps the existing value" semantics), adapted for the Company-default +
 * optional Branch-override model an ad account connection uses instead of
 * WhatsAppConfiguration's always-branch-mandatory one.
 */
@Service
@RequiredArgsConstructor
public class AdAccountConnectionService {
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final TenantAccess access;

    private final AdAccountConnectionRepository connectionRepo;
    private final CompanyRepository companyRepo;
    private final BranchRepository branchRepo;
    private final AdsProviderClientFactory providerClientFactory;
    private final AdSyncService adSyncService;

    @Transactional(readOnly = true)
    public List<AdAccountConnectionDto> getConnectionsByCompany(Long companyId) {
        access.companyWide(companyId);
        return connectionRepo.findByCompanyIdOrderByIdAsc(companyId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdAccountConnectionDto> getConnectionsByBranch(Long branchId) {
        access.branch(branchId);
        return connectionRepo.findByBranchIdOrderByIdAsc(branchId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public AdAccountConnectionDto saveConnection(AdAccountConnectionUpsertRequest req) {
        access.scope(req.getCompanyId(), req.getBranchId());
        if (req.getBranchId() == null) access.companyWide(req.getCompanyId());
        if (req.getId() != null) access.record(AdAccountConnection.class, req.getId());
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + req.getCompanyId()));

        Branch branch = null;
        if (req.getBranchId() != null) {
            branch = branchRepo.findById(req.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));
            if (!branch.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Branch " + req.getBranchId() + " does not belong to company " + req.getCompanyId());
            }
        }

        boolean isNew = req.getId() == null;
        AdAccountConnection connection;
        if (isNew) {
            connection = AdAccountConnection.builder().company(company).branch(branch).provider(req.getProvider()).build();
        } else {
            connection = connectionRepo.findById(req.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Ad account connection not found: " + req.getId()));
            connection.setCompany(company);
            connection.setBranch(branch);
            connection.setProvider(req.getProvider());
        }

        // At most one company-default (branch null) and one branch-override
        // per provider — MySQL/MariaDB unique indexes can't express this
        // (NULL != NULL), so it's enforced here.
        Optional<AdAccountConnection> conflict = branch == null
                ? connectionRepo.findByCompanyIdAndBranchIsNullAndProvider(company.getId(), req.getProvider())
                : connectionRepo.findByCompanyIdAndBranchIdAndProvider(company.getId(), branch.getId(), req.getProvider());
        if (conflict.isPresent() && !conflict.get().getId().equals(connection.getId())) {
            throw new IllegalArgumentException((branch == null ? "A company-default " : "A branch-override ")
                    + req.getProvider() + " connection already exists" + (branch != null ? " for this branch" : "") + ".");
        }

        // Digit-only normalization (Google's UI displays ids dashed, its API
        // rejects dashes) — done once here so every downstream caller
        // (provider clients, reconciliation) already sees the canonical form.
        String externalAccountId = req.getProvider() == AdProvider.GOOGLE
                ? digitsOnly(req.getExternalAccountId())
                : req.getExternalAccountId();

        // Defense-in-depth against the same underlying account being connected
        // twice within a company (e.g. as both the default and a branch
        // override) — see AdAttributionResolver's Javadoc for why this matters.
        List<AdAccountConnection> sameAccount = connectionRepo.findByCompanyIdOrderByIdAsc(company.getId()).stream()
                .filter(c -> c.getProvider() == req.getProvider() && externalAccountId.equals(c.getExternalAccountId()))
                .filter(c -> !c.getId().equals(connection.getId()))
                .toList();
        if (!sameAccount.isEmpty()) {
            throw new IllegalArgumentException("Ad account " + externalAccountId + " is already connected for this company under a different connection.");
        }

        connection.setExternalAccountId(externalAccountId);
        connection.setDisplayName(req.getDisplayName());
        connection.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);

        if (req.getProvider() == AdProvider.META) {
            if (req.getAccessToken() != null && !req.getAccessToken().isBlank()) {
                connection.setAccessToken(req.getAccessToken());
            } else if (isNew) {
                throw new IllegalArgumentException("Access Token is required for a new Meta ad account connection.");
            }
            connection.setAppId(req.getAppId());
        } else {
            connection.setGoogleClientId(req.getGoogleClientId());
            connection.setGoogleLoginCustomerId(digitsOnly(req.getGoogleLoginCustomerId()));
            setIfPresentElseRequireOnCreate(req.getGoogleDeveloperToken(), isNew, "Developer Token", connection::setGoogleDeveloperToken);
            setIfPresentElseRequireOnCreate(req.getGoogleClientSecret(), isNew, "Client Secret", connection::setGoogleClientSecret);
            setIfPresentElseRequireOnCreate(req.getGoogleRefreshToken(), isNew, "Refresh Token", connection::setGoogleRefreshToken);
            if (isNew && (req.getGoogleClientId() == null || req.getGoogleClientId().isBlank())) {
                throw new IllegalArgumentException("Client ID is required for a new Google Ads connection.");
            }
        }

        AdAccountConnection saved = connectionRepo.save(connection);

        // A brand-new connection has no daily spend rows yet — kick off a
        // historical backfill so "spend until now" isn't stuck at $0 until
        // the next periodic sync. Deferred to AFTER_COMMIT: backfillAsync
        // runs on a different thread with its own transaction, which would
        // not yet see this row if fired before this method's transaction
        // actually commits.
        if (isNew) {
            Long savedId = saved.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        adSyncService.backfillAsync(savedId);
                    }
                });
            } else {
                adSyncService.backfillAsync(savedId);
            }
        }

        return mapToDto(saved);
    }

    @Transactional
    public void deleteConnection(Long id) {
        access.record(com.leads.model.entity.AdAccountConnection.class, id);
        if (!connectionRepo.existsById(id)) {
            throw new IllegalArgumentException("Ad account connection not found: " + id);
        }
        connectionRepo.deleteById(id);
    }

    /** Module 19 P1 (CLAUDE.md hard rule 2): the connection is read in a short read; the provider call runs with none open. */
    public AdConnectionTestResult testConnection(Long id) {
        var read = new org.springframework.transaction.support.TransactionTemplate(transactions);
        read.setReadOnly(true);
        AdAccountConnection connection = read.execute(st -> access.record(AdAccountConnection.class, id));
        return providerClientFactory.resolve(connection.getProvider()).testConnection(connection);
    }

    /** Module 19 P1: the scope check for a manual backfill (was in the controller), then the async run. */
    public void requestBackfill(Long id) {
        access.record(AdAccountConnection.class, id);
        adSyncService.backfillAsync(id);
    }

    private void setIfPresentElseRequireOnCreate(String value, boolean isNew, String label, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        } else if (isNew) {
            throw new IllegalArgumentException(label + " is required for a new Google Ads connection.");
        }
    }

    private String digitsOnly(String value) {
        return value != null ? value.replaceAll("\\D", "") : null;
    }

    private AdAccountConnectionDto mapToDto(AdAccountConnection c) {
        return AdAccountConnectionDto.builder()
                .id(c.getId())
                .companyId(c.getCompany().getId())
                .branchId(c.getBranch() != null ? c.getBranch().getId() : null)
                .branchName(c.getBranch() != null ? c.getBranch().getName() : null)
                .provider(c.getProvider())
                .externalAccountId(c.getExternalAccountId())
                .displayName(c.getDisplayName())
                .isActive(c.getIsActive())
                .accessTokenMasked(maskToken(c.getAccessToken()))
                .appId(c.getAppId())
                .googleDeveloperTokenMasked(maskToken(c.getGoogleDeveloperToken()))
                .googleClientId(c.getGoogleClientId())
                .googleClientSecretMasked(maskToken(c.getGoogleClientSecret()))
                .googleRefreshTokenMasked(maskToken(c.getGoogleRefreshToken()))
                .googleLoginCustomerId(c.getGoogleLoginCustomerId())
                .lastSyncAt(c.getLastSyncAt())
                .lastSyncStatus(c.getLastSyncStatus())
                .lastSyncError(c.getLastSyncError())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() <= 8) return "••••••••";
        return token.substring(0, 4) + "••••••••" + token.substring(token.length() - 4);
    }
}
