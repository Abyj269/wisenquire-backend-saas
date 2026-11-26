package com.leads.service;

import com.leads.dto.delivery.CompanyDeliverySettingsDto;
import com.leads.dto.delivery.DeliveryFailureReasonDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.CompanyDeliverySettings;
import com.leads.model.entity.DeliveryFailureReason;
import com.leads.repository.CompanyDeliverySettingsRepository;
import com.leads.repository.CompanyRepository;
import com.leads.repository.DeliveryFailureReasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliverySettingsService {
    private final TenantAccess access;
    private final DeliverySettingsWriteScope writeScope;

    private final DeliveryFailureReasonRepository failureReasonRepo;
    private final CompanyDeliverySettingsRepository settingsRepo;
    private final CompanyRepository companyRepo;

    // --- Delivery Failure Reasons ---
    @Transactional(readOnly = true)
    public List<DeliveryFailureReasonDto> getFailureReasonsByCompany(Long companyId) {
        access.company(companyId);
        return failureReasonRepo.findByCompanyIdOrderBySortOrderAsc(companyId)
                .stream()
                .map(this::mapFailureReasonToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public DeliveryFailureReasonDto createFailureReason(Long companyId, DeliveryFailureReasonDto dto) {
        Company company = writeScope.check(companyId);

        DeliveryFailureReason reason = DeliveryFailureReason.builder()
                .company(company)
                .reasonText(dto.getReasonText())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        return mapFailureReasonToDto(failureReasonRepo.save(reason));
    }

    @Transactional
    public DeliveryFailureReasonDto updateFailureReason(Long id, DeliveryFailureReasonDto dto) {
        DeliveryFailureReason reason = access.record(DeliveryFailureReason.class, id);
        writeScope.check(reason.getCompany().getId());

        reason.setReasonText(dto.getReasonText());
        // NOT NULL columns: a field missing from the body keeps its value (was a 500 on the constraint).
        if (dto.getSortOrder() != null) reason.setSortOrder(dto.getSortOrder());
        if (dto.getIsActive() != null) reason.setIsActive(dto.getIsActive());

        return mapFailureReasonToDto(failureReasonRepo.save(reason));
    }

    @Transactional
    public void deleteFailureReason(Long id) {
        writeScope.check(access.record(DeliveryFailureReason.class, id).getCompany().getId());
        failureReasonRepo.deleteById(id);
    }

    /** Seeds five common reasons for a company that has none yet — fully editable/deletable afterward. */
    @Transactional
    public void seedDefaultFailureReasons(Company company) {
        if (!failureReasonRepo.findByCompanyIdOrderBySortOrderAsc(company.getId()).isEmpty()) {
            return;
        }
        failureReasonRepo.save(DeliveryFailureReason.builder().company(company).reasonText("Customer Not Available").sortOrder(1).isActive(true).build());
        failureReasonRepo.save(DeliveryFailureReason.builder().company(company).reasonText("Customer Refused").sortOrder(2).isActive(true).build());
        failureReasonRepo.save(DeliveryFailureReason.builder().company(company).reasonText("Wrong / Incomplete Address").sortOrder(3).isActive(true).build());
        failureReasonRepo.save(DeliveryFailureReason.builder().company(company).reasonText("Payment Not Ready").sortOrder(4).isActive(true).build());
        failureReasonRepo.save(DeliveryFailureReason.builder().company(company).reasonText("Other").sortOrder(5).isActive(true).build());
    }

    // --- Company Delivery Settings ---
    // NOT readOnly — the orElseGet fallback lazily creates and saves a default
    // settings row the first time a company is read before anyone's saved one;
    // see LeadSettingsService.getCompanyLeadSettings for why marking this
    // readOnly breaks that lazy-create path.
    @Transactional
    public CompanyDeliverySettingsDto getCompanyDeliverySettings(Long companyId) {
        access.company(companyId);
        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(companyId)
                .orElseGet(() -> {
                    Company company = companyRepo.findById(companyId).orElse(null);
                    if (company == null) return null;
                    CompanyDeliverySettings newSettings = CompanyDeliverySettings.builder()
                            .company(company)
                            .build();
                    return settingsRepo.save(newSettings);
                });

        return mapSettingsToDto(settings);
    }

    @Transactional
    public CompanyDeliverySettingsDto updateCompanyDeliverySettings(Long companyId, CompanyDeliverySettingsDto dto) {
        writeScope.check(companyId);
        CompanyDeliverySettings settings = settingsRepo.findByCompanyId(companyId)
                .orElseGet(() -> {
                    Company company = companyRepo.findById(companyId)
                            .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
                    return CompanyDeliverySettings.builder().company(company).build();
                });

        boolean wasEnabled = Boolean.TRUE.equals(settings.getDeliveryModuleEnabled());
        settings.setDeliveryModuleEnabled(dto.getDeliveryModuleEnabled() != null ? dto.getDeliveryModuleEnabled() : false);
        settings.setRequireApprovalForPostpone(dto.getRequireApprovalForPostpone() != null ? dto.getRequireApprovalForPostpone() : true);
        settings.setRequireApprovalForClose(dto.getRequireApprovalForClose() != null ? dto.getRequireApprovalForClose() : true);
        settings.setMaxDeliveryAttempts(dto.getMaxDeliveryAttempts());

        CompanyDeliverySettings saved = settingsRepo.save(settings);

        if (!wasEnabled && Boolean.TRUE.equals(saved.getDeliveryModuleEnabled())) {
            seedDefaultFailureReasons(saved.getCompany());
        }

        return mapSettingsToDto(saved);
    }

    public DeliveryFailureReasonDto mapFailureReasonToDto(DeliveryFailureReason r) {
        if (r == null) return null;
        return DeliveryFailureReasonDto.builder()
                .id(r.getId())
                .companyId(r.getCompany() != null ? r.getCompany().getId() : null)
                .reasonText(r.getReasonText())
                .sortOrder(r.getSortOrder())
                .isActive(r.getIsActive())
                .build();
    }

    public CompanyDeliverySettingsDto mapSettingsToDto(CompanyDeliverySettings s) {
        if (s == null) return null;
        return CompanyDeliverySettingsDto.builder()
                .id(s.getId())
                .companyId(s.getCompany().getId())
                .deliveryModuleEnabled(s.getDeliveryModuleEnabled())
                .requireApprovalForPostpone(s.getRequireApprovalForPostpone())
                .requireApprovalForClose(s.getRequireApprovalForClose())
                .maxDeliveryAttempts(s.getMaxDeliveryAttempts())
                .build();
    }
}
