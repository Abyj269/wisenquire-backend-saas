package com.leads.service;

import com.leads.dto.settings.CompanyLeadSettingsDto;
import com.leads.dto.settings.LeadLostReasonDto;
import com.leads.dto.settings.LeadPurposeDto;
import com.leads.dto.settings.LeadSourceDto;
import com.leads.dto.settings.LeadStatusDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.CompanyLeadSettings;
import com.leads.model.entity.LeadLostReason;
import com.leads.model.entity.LeadPurpose;
import com.leads.model.entity.LeadSource;
import com.leads.model.entity.LeadStatus;
import com.leads.model.enums.StageType;
import com.leads.repository.CompanyLeadSettingsRepository;
import com.leads.repository.CompanyRepository;
import com.leads.repository.LeadLostReasonRepository;
import com.leads.repository.LeadPurposeRepository;
import com.leads.repository.LeadRepository;
import com.leads.repository.LeadSourceRepository;
import com.leads.repository.LeadStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeadSettingsService {
    private final TenantAccess access;
    private final LeadSettingsWriteScope writeScope;

    private final LeadStatusRepository statusRepo;
    private final LeadPurposeRepository purposeRepo;
    private final LeadSourceRepository sourceRepo;
    private final LeadLostReasonRepository lostReasonRepo;
    private final CompanyLeadSettingsRepository settingsRepo;
    private final CompanyRepository companyRepo;
    private final LeadRepository leadRepo;

    /**
     * The pipeline always opens with exactly one OPEN-stage status and always
     * closes with at least one CONVERTED/LOST-stage status. Custom statuses a
     * company adds can only ever land strictly between those two anchors —
     * nothing can be inserted before the opening stage or after the closing
     * ones. {@link #renumberPipeline} re-derives sortOrder from these buckets
     * after every mutation so the invariant can never drift out of sync.
     */
    private static final Set<StageType> FIRST_ANCHOR_TYPES = EnumSet.of(StageType.OPEN);
    private static final Set<StageType> LAST_ANCHOR_TYPES = EnumSet.of(StageType.CONVERTED, StageType.LOST);

    /**
     * Stage types whose role can never be reassigned — the position anchors
     * above (OPEN/CONVERTED/LOST) plus ASSIGNED, which doesn't anchor a
     * pipeline position but is just as load-bearing: LeadService's
     * assignment-triggered auto-status-change (first assignment on a still-Open
     * lead auto-advances it) resolves "the Assigned stage" by stageType, not
     * name, so there must always be exactly one status with this stageType
     * and it must never be silently repurposed into a regular stage.
     */
    private static final Set<StageType> ROLE_LOCKED_TYPES = EnumSet.of(StageType.OPEN, StageType.ASSIGNED, StageType.CONVERTED, StageType.LOST);

    // --- Lead Statuses ---
    @Transactional(readOnly = true)
    public List<LeadStatusDto> getStatusesByCompany(Long companyId) {
        access.company(companyId);
        return statusRepo.findByCompanyIdOrderBySortOrderAsc(companyId)
                .stream()
                .map(this::mapStatusToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeadStatusDto createStatus(Long companyId, LeadStatusDto dto) {
        Company company = writeScope.check(companyId);

        if (dto.getStageType() == null || ROLE_LOCKED_TYPES.contains(dto.getStageType())) {
            throw new IllegalArgumentException(
                    "The pipeline's opening stage, its \"Assigned\" stage, and its closing (Converted/Lost) stages are fixed — " +
                    "choose an in-progress stage type for a custom status.");
        }

        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            // Unset previous default
            statusRepo.findByCompanyIdAndIsDefaultTrue(companyId).ifPresent(s -> {
                s.setIsDefault(false);
                statusRepo.save(s);
            });
        }

        LeadStatus status = LeadStatus.builder()
                .company(company)
                .statusName(dto.getStatusName())
                .statusCode(dto.getStatusCode().toUpperCase())
                .colorCode(dto.getColorCode())
                .stageType(dto.getStageType())
                // Placeholder — renumberPipeline() below always slots new
                // middle statuses in just before the closing anchor(s).
                .sortOrder(Integer.MAX_VALUE)
                .isDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                // Always false here — only DataInitializer's seeded pipeline
                // stages are system-defined; anything a user adds via this
                // endpoint is a regular, deletable custom status.
                .isSystemDefined(false)
                .build();

        LeadStatus saved = statusRepo.save(status);
        renumberPipeline(companyId);
        return mapStatusToDto(statusRepo.findById(saved.getId()).orElseThrow());
    }

    @Transactional
    public LeadStatusDto updateStatus(Long id, LeadStatusDto dto) {
        writeScope.check(access.record(com.leads.model.entity.LeadStatus.class, id).getCompany().getId());
        LeadStatus status = statusRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Status not found: " + id));

        boolean wasRoleLocked = ROLE_LOCKED_TYPES.contains(status.getStageType());
        boolean willBeRoleLocked = dto.getStageType() != null && ROLE_LOCKED_TYPES.contains(dto.getStageType());

        if (wasRoleLocked != willBeRoleLocked) {
            throw new IllegalArgumentException(
                    "This stage's role in the pipeline (Open / Assigned / Converted / Lost) can't be changed to or from a regular stage.");
        }
        if (LAST_ANCHOR_TYPES.contains(status.getStageType()) && !status.getStageType().equals(dto.getStageType())) {
            assertAnotherClosingStageRemains(status);
        }

        if (Boolean.TRUE.equals(dto.getIsDefault()) && !Boolean.TRUE.equals(status.getIsDefault())) {
            statusRepo.findByCompanyIdAndIsDefaultTrue(status.getCompany().getId()).ifPresent(s -> {
                s.setIsDefault(false);
                statusRepo.save(s);
            });
        }

        status.setStatusName(dto.getStatusName());
        status.setStatusCode(dto.getStatusCode().toUpperCase());
        status.setColorCode(dto.getColorCode());
        if (!wasRoleLocked) {
            // Role-locked stage types (Open/Assigned/Converted/Lost) are locked; only a regular status's stage type may change.
            status.setStageType(dto.getStageType());
        }
        status.setIsDefault(dto.getIsDefault());
        status.setIsActive(dto.getIsActive());

        LeadStatus saved = statusRepo.save(status);
        renumberPipeline(saved.getCompany().getId());
        return mapStatusToDto(statusRepo.findById(saved.getId()).orElseThrow());
    }

    @Transactional
    public void deleteStatus(Long id) {
        writeScope.check(access.record(com.leads.model.entity.LeadStatus.class, id).getCompany().getId());
        LeadStatus status = statusRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Status not found: " + id));

        if (Boolean.TRUE.equals(status.getIsSystemDefined())) {
            throw new IllegalArgumentException(
                    "\"" + status.getStatusName() + "\" is one of the company's built-in pipeline stages and can't be deleted — only custom statuses you've added can be.");
        }
        if (FIRST_ANCHOR_TYPES.contains(status.getStageType())) {
            throw new IllegalArgumentException("The pipeline's opening stage can't be deleted.");
        }
        if (LAST_ANCHOR_TYPES.contains(status.getStageType())) {
            assertAnotherClosingStageRemains(status);
        }
        long leadsUsingStatus = leadRepo.countByStatusId(id);
        if (leadsUsingStatus > 0) {
            throw new IllegalArgumentException(
                    "\"" + status.getStatusName() + "\" is currently assigned to " + leadsUsingStatus +
                    " lead(s) — move them to another status before deleting this one.");
        }

        Long companyId = status.getCompany().getId();
        statusRepo.deleteById(id);
        renumberPipeline(companyId);
    }

    /**
     * Seeds the standard 6-stage pipeline (Lead Opened -> Assigned -> In
     * Progress -> Quotation Provided -> Quote Approved -> Converted/Closed,
     * plus a Lost/Disqualified closing stage) for a company that has none
     * yet. Called both for every newly created company and, defensively,
     * from DataInitializer, so no company can ever end up with an empty
     * pipeline board.
     */
    @Transactional
    public void seedDefaultPipeline(Company company) {
        if (!statusRepo.findByCompanyIdOrderBySortOrderAsc(company.getId()).isEmpty()) {
            return;
        }
        statusRepo.save(LeadStatus.builder().company(company).statusName("Lead Opened").statusCode("OPENED")
                .colorCode("#6366F1").stageType(StageType.OPEN).sortOrder(1).isDefault(true).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Lead Assigned").statusCode("ASSIGNED")
                .colorCode("#3B82F6").stageType(StageType.ASSIGNED).sortOrder(2).isDefault(false).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Lead In Progress").statusCode("IN_PROGRESS")
                .colorCode("#8B5CF6").stageType(StageType.IN_PROGRESS).sortOrder(3).isDefault(false).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Quotation Provided").statusCode("QUOTATION_PROVIDED")
                .colorCode("#EC4899").stageType(StageType.QUOTATION_PROVIDED).sortOrder(4).isDefault(false).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Quote Approved").statusCode("QUOTE_APPROVED")
                .colorCode("#F59E0B").stageType(StageType.QUOTE_APPROVED).sortOrder(5).isDefault(false).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Lead Converted / Closed").statusCode("CONVERTED")
                .colorCode("#10B981").stageType(StageType.CONVERTED).sortOrder(6).isDefault(false).isActive(true).isSystemDefined(true).build());
        statusRepo.save(LeadStatus.builder().company(company).statusName("Lost / Disqualified").statusCode("LOST")
                .colorCode("#EF4444").stageType(StageType.LOST).sortOrder(7).isDefault(false).isActive(true).isSystemDefined(true).build());
    }

    private void assertAnotherClosingStageRemains(LeadStatus beingRemoved) {
        long remaining = statusRepo.findByCompanyIdOrderBySortOrderAsc(beingRemoved.getCompany().getId()).stream()
                .filter(s -> LAST_ANCHOR_TYPES.contains(s.getStageType()) && !s.getId().equals(beingRemoved.getId()))
                .count();
        if (remaining == 0) {
            throw new IllegalArgumentException("At least one closing stage (Converted or Lost) must remain in the pipeline.");
        }
    }

    /**
     * Re-derives sortOrder for the whole pipeline from three buckets — opening
     * anchor(s), regular middle stages (in their existing relative order),
     * closing anchor(s) — so the "nothing before Open / nothing after
     * Converted-Lost" invariant holds after every create/update/delete
     * without the caller having to compute positions itself.
     */
    private void renumberPipeline(Long companyId) {
        List<LeadStatus> all = statusRepo.findByCompanyIdOrderBySortOrderAsc(companyId);

        List<LeadStatus> opening = all.stream().filter(s -> FIRST_ANCHOR_TYPES.contains(s.getStageType())).collect(Collectors.toList());
        List<LeadStatus> closing = all.stream().filter(s -> LAST_ANCHOR_TYPES.contains(s.getStageType())).collect(Collectors.toList());
        List<LeadStatus> middle = all.stream()
                .filter(s -> !FIRST_ANCHOR_TYPES.contains(s.getStageType()) && !LAST_ANCHOR_TYPES.contains(s.getStageType()))
                .collect(Collectors.toList());

        int order = 1;
        for (LeadStatus s : opening) s.setSortOrder(order++);
        for (LeadStatus s : middle) s.setSortOrder(order++);
        for (LeadStatus s : closing) s.setSortOrder(order++);

        statusRepo.saveAll(opening);
        statusRepo.saveAll(middle);
        statusRepo.saveAll(closing);
    }

    // --- Lead Purposes ---
    @Transactional(readOnly = true)
    public List<LeadPurposeDto> getPurposesByCompany(Long companyId) {
        access.company(companyId);
        return purposeRepo.findByCompanyIdOrderBySortOrderAsc(companyId)
                .stream()
                .map(this::mapPurposeToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeadPurposeDto createPurpose(Long companyId, LeadPurposeDto dto) {
        Company company = writeScope.check(companyId);

        LeadPurpose purpose = LeadPurpose.builder()
                .company(company)
                .purposeName(dto.getPurposeName())
                .classification(dto.getClassification() != null ? dto.getClassification() : com.leads.model.enums.PurposeClassification.POTENTIAL)
                .description(dto.getDescription())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        return mapPurposeToDto(purposeRepo.save(purpose));
    }

    @Transactional
    public LeadPurposeDto updatePurpose(Long id, LeadPurposeDto dto) {
        writeScope.check(access.record(com.leads.model.entity.LeadPurpose.class, id).getCompany().getId());
        LeadPurpose purpose = purposeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purpose not found: " + id));

        purpose.setPurposeName(dto.getPurposeName());
        purpose.setDescription(dto.getDescription());
        if (dto.getClassification() != null) purpose.setClassification(dto.getClassification());
        purpose.setSortOrder(dto.getSortOrder());
        purpose.setIsActive(dto.getIsActive());

        return mapPurposeToDto(purposeRepo.save(purpose));
    }

    // --- Lead Lost Reasons ---
    @Transactional(readOnly = true)
    public List<LeadLostReasonDto> getLostReasonsByCompany(Long companyId) {
        access.company(companyId);
        return lostReasonRepo.findByCompanyIdOrderBySortOrderAsc(companyId)
                .stream()
                .map(this::mapLostReasonToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeadLostReasonDto createLostReason(Long companyId, LeadLostReasonDto dto) {
        Company company = writeScope.check(companyId);

        LeadLostReason reason = LeadLostReason.builder()
                .company(company)
                .reasonText(dto.getReasonText())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        return mapLostReasonToDto(lostReasonRepo.save(reason));
    }

    @Transactional
    public LeadLostReasonDto updateLostReason(Long id, LeadLostReasonDto dto) {
        writeScope.check(access.record(com.leads.model.entity.LeadLostReason.class, id).getCompany().getId());
        LeadLostReason reason = lostReasonRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lost reason not found: " + id));

        reason.setReasonText(dto.getReasonText());
        reason.setSortOrder(dto.getSortOrder());
        reason.setIsActive(dto.getIsActive());

        return mapLostReasonToDto(lostReasonRepo.save(reason));
    }

    @Transactional
    public void deleteLostReason(Long id) {
        writeScope.check(access.record(com.leads.model.entity.LeadLostReason.class, id).getCompany().getId());
        lostReasonRepo.deleteById(id);
    }

    /** Seeds five common reasons for a company that has none yet — fully editable/deletable afterward, unlike the seeded pipeline stages/sources. */
    @Transactional
    public void seedDefaultLostReasons(Company company) {
        if (!lostReasonRepo.findByCompanyIdOrderBySortOrderAsc(company.getId()).isEmpty()) {
            return;
        }
        lostReasonRepo.save(LeadLostReason.builder().company(company).reasonText("Budget Constraints").sortOrder(1).isActive(true).build());
        lostReasonRepo.save(LeadLostReason.builder().company(company).reasonText("Chose a Competitor").sortOrder(2).isActive(true).build());
        lostReasonRepo.save(LeadLostReason.builder().company(company).reasonText("Unresponsive / No Contact").sortOrder(3).isActive(true).build());
        lostReasonRepo.save(LeadLostReason.builder().company(company).reasonText("Timing Not Right").sortOrder(4).isActive(true).build());
        lostReasonRepo.save(LeadLostReason.builder().company(company).reasonText("Other").sortOrder(5).isActive(true).build());
    }

    @Transactional
    public void deletePurpose(Long id) {
        writeScope.check(access.record(com.leads.model.entity.LeadPurpose.class, id).getCompany().getId());
        purposeRepo.deleteById(id);
    }

    // --- Lead Sources ---
    @Transactional(readOnly = true)
    public List<LeadSourceDto> getSourcesByCompany(Long companyId) {
        access.company(companyId);
        return sourceRepo.findByCompanyIdOrderBySortOrderAsc(companyId)
                .stream()
                .map(this::mapSourceToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeadSourceDto createSource(Long companyId, LeadSourceDto dto) {
        Company company = writeScope.check(companyId);

        LeadSource source = LeadSource.builder()
                .company(company)
                .sourceName(dto.getSourceName())
                .sourceCode(normalizeSourceCode(dto.getSourceCode()))
                .icon(dto.getIcon())
                .colorCode(dto.getColorCode() != null ? dto.getColorCode() : "#6366F1")
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : sourceRepo.findByCompanyIdOrderBySortOrderAsc(companyId).size() + 1)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                // Always false here — only seedDefaultSources' built-in sources
                // are system-defined; anything added via this endpoint is a
                // regular, fully editable and deletable custom source.
                .isSystemDefined(false)
                .build();

        return mapSourceToDto(sourceRepo.save(source));
    }

    @Transactional
    public LeadSourceDto updateSource(Long id, LeadSourceDto dto) {
        writeScope.check(access.record(com.leads.model.entity.LeadSource.class, id).getCompany().getId());
        LeadSource source = sourceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source not found: " + id));

        if (Boolean.TRUE.equals(source.getIsSystemDefined())) {
            throw new IllegalArgumentException(
                    "\"" + source.getSourceName() + "\" is one of the company's built-in lead sources and can't be edited — only custom sources you've added can be.");
        }

        source.setSourceName(dto.getSourceName());
        source.setSourceCode(normalizeSourceCode(dto.getSourceCode()));
        source.setIcon(dto.getIcon());
        source.setColorCode(dto.getColorCode());
        source.setSortOrder(dto.getSortOrder());
        source.setIsActive(dto.getIsActive());

        return mapSourceToDto(sourceRepo.save(source));
    }

    @Transactional
    public void deleteSource(Long id) {
        writeScope.check(access.record(com.leads.model.entity.LeadSource.class, id).getCompany().getId());
        LeadSource source = sourceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source not found: " + id));

        if (Boolean.TRUE.equals(source.getIsSystemDefined())) {
            throw new IllegalArgumentException(
                    "\"" + source.getSourceName() + "\" is one of the company's built-in lead sources and can't be deleted — only custom sources you've added can be.");
        }
        long leadsUsingSource = leadRepo.countBySourceId(id);
        if (leadsUsingSource > 0) {
            throw new IllegalArgumentException(
                    "\"" + source.getSourceName() + "\" is currently assigned to " + leadsUsingSource + " lead(s) — reassign them to another source before deleting this one.");
        }

        sourceRepo.deleteById(id);
    }

    /**
     * Seeds the six standard lead sources (Direct Lead, Inbound Call,
     * Whatsapp, Whatsapp Marketing, Website, Instagram) for a company that
     * has none yet — all isSystemDefined(true), so they're permanent but a
     * company can freely add its own sources alongside them. Called both for
     * every newly created company and, defensively, from DataInitializer.
     */
    @Transactional
    public void seedDefaultSources(Company company) {
        if (!sourceRepo.findByCompanyIdOrderBySortOrderAsc(company.getId()).isEmpty()) {
            return;
        }
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Direct Lead").sourceCode("DIRECT")
                .icon("target").colorCode("#6366F1").sortOrder(1).isActive(true).isSystemDefined(true).build());
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Inbound Call").sourceCode("INBOUND_CALL")
                .icon("phone").colorCode("#3B82F6").sortOrder(2).isActive(true).isSystemDefined(true).build());
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Whatsapp").sourceCode("WHATSAPP")
                .icon("message-square").colorCode("#25D366").sortOrder(3).isActive(true).isSystemDefined(true).build());
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Whatsapp Marketing").sourceCode("WHATSAPP_MARKETING")
                .icon("megaphone").colorCode("#128C7E").sortOrder(4).isActive(true).isSystemDefined(true).build());
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Website").sourceCode("WEBSITE")
                .icon("globe").colorCode("#0EA5E9").sortOrder(5).isActive(true).isSystemDefined(true).build());
        sourceRepo.save(LeadSource.builder().company(company).sourceName("Instagram").sourceCode("INSTAGRAM")
                .icon("instagram").colorCode("#E1306C").sortOrder(6).isActive(true).isSystemDefined(true).build());
    }

    /** Resolves a built-in source by its fixed code (e.g. "DIRECT", "WHATSAPP") — used to default/auto-tag leads created without an explicit source. */
    @Transactional(readOnly = true)
    public LeadSource getSystemSource(Long companyId, String sourceCode) {
        access.company(companyId);
        return sourceRepo.findByCompanyIdAndSourceCode(companyId, sourceCode)
                .orElseGet(() -> sourceRepo.findByCompanyIdOrderBySortOrderAsc(companyId).stream().findFirst().orElse(null));
    }

    private String normalizeSourceCode(String raw) {
        return raw.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }

    // --- Company Settings (Product linkage, auto assign) ---
    // NOT readOnly: the orElseGet fallback below lazily creates and saves a
    // default settings row the first time a company is read before anyone's
    // ever saved one — a real write, not just a read. mysql-connector-j
    // enforces Spring's readOnly hint as an actual read-only JDBC connection,
    // so marking this readOnly made that lazy-create throw
    // "Connection is read-only" for any company whose settings row didn't
    // exist yet (masked by Spring Security as a misleading 403, since the
    // resulting 500 gets internally forwarded to /error, which loses the
    // authenticated context on that forwarded dispatch).
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public CompanyLeadSettingsDto getCompanyLeadSettings(Long companyId) {
        access.company(companyId);
        CompanyLeadSettings settings = settingsRepo.findByCompanyId(companyId)
                .orElseGet(() -> {
                    Company company = companyRepo.findById(companyId).orElse(null);
                    if (company == null) return null;
                    CompanyLeadSettings newSettings = CompanyLeadSettings.builder()
                            .company(company)
                            .linkProductEnabled(true)
                            .autoAssignEnabled(false)
                            .build();
                    return settingsRepo.save(newSettings);
                });

        return mapSettingsToDto(settings);
    }

    @Transactional
    public CompanyLeadSettingsDto updateCompanyLeadSettings(Long companyId, CompanyLeadSettingsDto dto) {
        writeScope.check(companyId);
        CompanyLeadSettings settings = settingsRepo.findByCompanyId(companyId)
                .orElseGet(() -> {
                    Company company = companyRepo.findById(companyId)
                            .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
                    return CompanyLeadSettings.builder().company(company).build();
                });

        settings.setLinkProductEnabled(dto.getLinkProductEnabled());
        settings.setAutoAssignEnabled(dto.getAutoAssignEnabled());
        settings.setAutoAssignStrategy(dto.getAutoAssignStrategy());
        if (dto.getCustomerLostAfterDays() != null) {
            settings.setCustomerLostAfterDays(dto.getCustomerLostAfterDays());
        }
        if (dto.getWhatsappAutoCreateLeadEnabled() != null) {
            settings.setWhatsappAutoCreateLeadEnabled(dto.getWhatsappAutoCreateLeadEnabled());
        }

        if (dto.getAutomationConflictStrategy() != null) {
            settings.setAutomationConflictStrategy(dto.getAutomationConflictStrategy());
        }

        if (dto.getDefaultStatusId() != null) {
            // F-10: the default status must be in scope and belong to this company.
            LeadStatus status = access.record(LeadStatus.class, dto.getDefaultStatusId());
            access.sameCompany(companyId, status.getCompany());
            settings.setDefaultStatus(status);
        }

        return mapSettingsToDto(settingsRepo.save(settings));
    }

    public LeadStatusDto mapStatusToDto(LeadStatus s) {
        if (s == null) return null;
        return LeadStatusDto.builder()
                .id(s.getId())
                .companyId(s.getCompany() != null ? s.getCompany().getId() : null)
                .statusName(s.getStatusName())
                .statusCode(s.getStatusCode())
                .colorCode(s.getColorCode())
                .stageType(s.getStageType())
                .sortOrder(s.getSortOrder())
                .isDefault(s.getIsDefault())
                .isActive(s.getIsActive())
                .isSystemDefined(s.getIsSystemDefined())
                .build();
    }

    public LeadPurposeDto mapPurposeToDto(LeadPurpose p) {
        if (p == null) return null;
        return LeadPurposeDto.builder()
                .id(p.getId())
                .companyId(p.getCompany() != null ? p.getCompany().getId() : null)
                .purposeName(p.getPurposeName())
                .classification(p.getClassification())
                .description(p.getDescription())
                .sortOrder(p.getSortOrder())
                .isActive(p.getIsActive())
                .build();
    }

    public LeadLostReasonDto mapLostReasonToDto(LeadLostReason r) {
        if (r == null) return null;
        return LeadLostReasonDto.builder()
                .id(r.getId())
                .companyId(r.getCompany() != null ? r.getCompany().getId() : null)
                .reasonText(r.getReasonText())
                .sortOrder(r.getSortOrder())
                .isActive(r.getIsActive())
                .build();
    }

    public LeadSourceDto mapSourceToDto(LeadSource s) {
        if (s == null) return null;
        return LeadSourceDto.builder()
                .id(s.getId())
                .companyId(s.getCompany() != null ? s.getCompany().getId() : null)
                .sourceName(s.getSourceName())
                .sourceCode(s.getSourceCode())
                .icon(s.getIcon())
                .colorCode(s.getColorCode())
                .sortOrder(s.getSortOrder())
                .isActive(s.getIsActive())
                .isSystemDefined(s.getIsSystemDefined())
                .build();
    }

    public CompanyLeadSettingsDto mapSettingsToDto(CompanyLeadSettings s) {
        if (s == null) return null;
        return CompanyLeadSettingsDto.builder()
                .id(s.getId())
                .companyId(s.getCompany().getId())
                .linkProductEnabled(s.getLinkProductEnabled())
                .autoAssignEnabled(s.getAutoAssignEnabled())
                .autoAssignStrategy(s.getAutoAssignStrategy())
                .defaultStatusId(s.getDefaultStatus() != null ? s.getDefaultStatus().getId() : null)
                .defaultStatusName(s.getDefaultStatus() != null ? s.getDefaultStatus().getStatusName() : null)
                .customerLostAfterDays(s.getCustomerLostAfterDays())
                .whatsappAutoCreateLeadEnabled(s.getWhatsappAutoCreateLeadEnabled())
                .automationConflictStrategy(s.getAutomationConflictStrategy() != null ? s.getAutomationConflictStrategy() : "FIRST_MATCH")
                .build();
    }
}
