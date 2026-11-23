package com.leads.service;

import com.leads.dto.ai.AiBranchSettingsDto;
import com.leads.dto.ai.AiBranchSettingsUpsertRequest;
import com.leads.model.entity.AiBranchSettings;
import com.leads.model.entity.Branch;
import com.leads.repository.AiBranchSettingsRepository;
import com.leads.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiBranchSettingsService {

    private final TenantAccess access;
    private final AiBranchSettingsRepository settingsRepo;
    private final BranchRepository branchRepo;

    /**
     * The effective settings for a branch — the persisted row if an admin
     * has ever saved one, otherwise a transient (never-persisted) instance
     * carrying the same conservative defaults declared on the entity. Caps
     * are opt-out, not opt-in: a branch that has never been touched must
     * never behave as "unlimited".
     */
    @Transactional(readOnly = true)
    public AiBranchSettings resolveEffective(Long branchId) {
        return settingsRepo.findByBranchId(branchId).orElseGet(() -> AiBranchSettings.builder().build());
    }

    @Transactional(readOnly = true)
    public AiBranchSettingsDto get(Long branchId) {
        access.branch(branchId);
        return toDto(branchId, resolveEffective(branchId));
    }

    @Transactional
    public AiBranchSettingsDto upsert(Long branchId, AiBranchSettingsUpsertRequest req) {
        access.branch(branchId);
        Branch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));

        AiBranchSettings settings = settingsRepo.findByBranchId(branchId)
                .orElseGet(() -> AiBranchSettings.builder().branch(branch).company(branch.getCompany()).build());

        if (req.getContextMaxMessages() != null) settings.setContextMaxMessages(req.getContextMaxMessages());
        if (req.getContextMaxLookbackHours() != null) settings.setContextMaxLookbackHours(req.getContextMaxLookbackHours());
        if (req.getContextMaxChars() != null) settings.setContextMaxChars(req.getContextMaxChars());
        if (req.getDailyRequestCap() != null) settings.setDailyRequestCap(req.getDailyRequestCap());
        if (req.getMonthlyRequestCap() != null) settings.setMonthlyRequestCap(req.getMonthlyRequestCap());
        if (req.getMonthlyTokenCap() != null) settings.setMonthlyTokenCap(req.getMonthlyTokenCap());
        if (req.getNotifyThresholdPercent() != null) settings.setNotifyThresholdPercent(req.getNotifyThresholdPercent());
        if (req.getReplySuggestionEnabled() != null) settings.setReplySuggestionEnabled(req.getReplySuggestionEnabled());
        if (req.getMessageImprovementEnabled() != null) settings.setMessageImprovementEnabled(req.getMessageImprovementEnabled());
        if (req.getTranslationEnabled() != null) settings.setTranslationEnabled(req.getTranslationEnabled());
        if (req.getConversionScoringEnabled() != null) settings.setConversionScoringEnabled(req.getConversionScoringEnabled());

        return toDto(branchId, settingsRepo.save(settings));
    }

    private AiBranchSettingsDto toDto(Long branchId, AiBranchSettings s) {
        return AiBranchSettingsDto.builder()
                .branchId(branchId)
                .contextMaxMessages(s.getContextMaxMessages())
                .contextMaxLookbackHours(s.getContextMaxLookbackHours())
                .contextMaxChars(s.getContextMaxChars())
                .dailyRequestCap(s.getDailyRequestCap())
                .monthlyRequestCap(s.getMonthlyRequestCap())
                .monthlyTokenCap(s.getMonthlyTokenCap())
                .notifyThresholdPercent(s.getNotifyThresholdPercent())
                .replySuggestionEnabled(s.getReplySuggestionEnabled())
                .messageImprovementEnabled(s.getMessageImprovementEnabled())
                .translationEnabled(s.getTranslationEnabled())
                .conversionScoringEnabled(s.getConversionScoringEnabled())
                .build();
    }
}
