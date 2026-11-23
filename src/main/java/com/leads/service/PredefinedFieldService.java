package com.leads.service;

import com.leads.dto.settings.PredefinedFieldConfigDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.PredefinedFieldConfig;
import com.leads.model.enums.PredefinedLeadField;
import com.leads.repository.CompanyRepository;
import com.leads.repository.PredefinedFieldConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hide/mandatory config for native (predefined) Lead fields — a closed set
 * (see PredefinedLeadField), not admin-extensible like DynamicFieldDefinition.
 * Locked fields (FIRST_NAME/PHONE/PURPOSE/SOURCE) are always visible+mandatory
 * and refuse updates here; the other 8 are per-company configurable.
 */
@Service
@RequiredArgsConstructor
public class PredefinedFieldService {
    private final TenantAccess access;

    // Same curated codes as the frontend's country-codes.ts list (plus OTHER)
    // and the fixed 4-language set — kept small and server-validated so a
    // client can't smuggle an arbitrary string into these columns.
    private static final Set<String> NATIONALITY_CODES = Set.of(
            "AE", "SA", "QA", "KW", "BH", "OM", "EG", "JO", "LB", "IQ", "TR",
            "IN", "PK", "BD", "LK", "PH", "ID", "MY", "GB", "US", "OTHER"
    );
    private static final Set<String> LANGUAGE_CODES = Set.of("EN", "AR", "FR", "DE");

    private final PredefinedFieldConfigRepository configRepo;
    private final CompanyRepository companyRepo;

    @Transactional
    public java.util.List<PredefinedFieldConfigDto> getConfigsByCompany(Long companyId) {
        access.company(companyId);
        return Arrays.stream(PredefinedLeadField.values())
                .map(field -> mapToDto(getOrCreate(companyId, field)))
                .collect(Collectors.toList());
    }

    @Transactional
    public PredefinedFieldConfigDto updateConfig(Long companyId, String fieldKeyRaw, PredefinedFieldConfigDto dto) {
        access.company(companyId);
        PredefinedLeadField field = parseFieldKey(fieldKeyRaw);
        if (field.isLocked()) {
            throw new IllegalArgumentException(
                    "\"" + field.getLabel() + "\" is always on for every lead and can't be changed.");
        }

        PredefinedFieldConfig config = getOrCreate(companyId, field);
        if (dto.getIsVisible() != null) {
            config.setIsVisible(dto.getIsVisible());
        }
        if (dto.getIsMandatory() != null) {
            config.setIsMandatory(dto.getIsMandatory());
        }
        return mapToDto(configRepo.save(config));
    }

    /** Used by LeadService to enforce mandatory-ness at create/update time — one query per lead, not 12. */
    @Transactional
    public Map<PredefinedLeadField, PredefinedFieldConfigDto> getConfigMap(Long companyId) {
        Map<PredefinedLeadField, PredefinedFieldConfigDto> map = new EnumMap<>(PredefinedLeadField.class);
        for (PredefinedLeadField field : PredefinedLeadField.values()) {
            map.put(field, mapToDto(getOrCreate(companyId, field)));
        }
        return map;
    }

    public void enforceMandatory(Long companyId, Map<PredefinedLeadField, Object> nativeValues) {
        Map<PredefinedLeadField, PredefinedFieldConfigDto> configs = getConfigMap(companyId);
        for (Map.Entry<PredefinedLeadField, Object> entry : nativeValues.entrySet()) {
            PredefinedFieldConfigDto config = configs.get(entry.getKey());
            if (config == null || !Boolean.TRUE.equals(config.getIsMandatory())) {
                continue;
            }
            Object value = entry.getValue();
            boolean missing = value == null || (value instanceof String && ((String) value).isBlank());
            if (missing) {
                throw new IllegalArgumentException("Mandatory field '" + entry.getKey().getLabel() + "' is required");
            }
        }
    }

    /**
     * Lead Purpose is locked (always mandatory per spec), so unlike the 8
     * configurable fields it's not looked up from PredefinedFieldConfig at
     * all — but it IS guarded by a safety net: only enforced once the
     * company actually has at least one active purpose configured, so this
     * can never lock a company out of creating leads just because nobody's
     * set up purposes yet. Caller (LeadService) supplies that check since it
     * already has LeadPurposeRepository injected.
     */
    public void enforcePurposeMandatory(Long purposeId, boolean hasAnyActivePurpose) {
        if (purposeId == null && hasAnyActivePurpose) {
            throw new IllegalArgumentException("Mandatory field 'Lead Purpose' is required");
        }
    }

    public void validateNativeFieldValues(String nationality, String preferredLanguage) {
        if (nationality != null && !nationality.isBlank() && !NATIONALITY_CODES.contains(nationality.toUpperCase())) {
            throw new IllegalArgumentException("Unrecognized nationality code: " + nationality);
        }
        if (preferredLanguage != null && !preferredLanguage.isBlank() && !LANGUAGE_CODES.contains(preferredLanguage.toUpperCase())) {
            throw new IllegalArgumentException("Unrecognized preferred language code: " + preferredLanguage);
        }
    }

    private PredefinedFieldConfig getOrCreate(Long companyId, PredefinedLeadField field) {
        return configRepo.findByCompanyIdAndFieldKey(companyId, field)
                .orElseGet(() -> {
                    Company company = companyRepo.findById(companyId)
                            .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
                    return configRepo.save(PredefinedFieldConfig.builder()
                            .company(company)
                            .fieldKey(field)
                            .isVisible(true)
                            .isMandatory(field.isLocked())
                            .build());
                });
    }

    private PredefinedLeadField parseFieldKey(String raw) {
        try {
            return PredefinedLeadField.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown predefined field: " + raw);
        }
    }

    private PredefinedFieldConfigDto mapToDto(PredefinedFieldConfig config) {
        return PredefinedFieldConfigDto.builder()
                .fieldKey(config.getFieldKey().name())
                .fieldLabel(config.getFieldKey().getLabel())
                .isVisible(config.getIsVisible())
                .isMandatory(config.getIsMandatory())
                .isLocked(config.getFieldKey().isLocked())
                .build();
    }
}
