package com.leads.service;

import com.leads.dto.settings.DynamicFieldDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.DynamicFieldDefinition;
import com.leads.repository.CompanyRepository;
import com.leads.repository.DynamicFieldDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DynamicFieldService {
    private final TenantAccess access;

    private final DynamicFieldDefinitionRepository dynamicFieldRepo;
    private final CompanyRepository companyRepo;

    @Transactional(readOnly = true)
    public List<DynamicFieldDto> getFieldsByCompanyAndModule(Long companyId, String module) {
        access.company(companyId);
        return dynamicFieldRepo.findByCompanyIdAndModuleAndIsActiveTrueOrderBySortOrderAsc(companyId, module)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DynamicFieldDto> getAllFieldsByCompany(Long companyId, String module) {
        access.company(companyId);
        return dynamicFieldRepo.findByCompanyIdAndModuleOrderBySortOrderAsc(companyId, module)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public DynamicFieldDto createField(Long companyId, DynamicFieldDto dto) {
        access.company(companyId);
        validateDefinition(dto);
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));

        DynamicFieldDefinition field = DynamicFieldDefinition.builder()
                .company(company)
                .module(dto.getModule() != null ? dto.getModule() : "LEAD")
                .fieldName(dto.getFieldName().trim().toLowerCase().replaceAll("\\s+", "_"))
                .fieldLabel(dto.getFieldLabel())
                .fieldType(dto.getFieldType())
                .isMandatory(dto.getIsMandatory() != null ? dto.getIsMandatory() : false)
                .defaultValue(dto.getDefaultValue())
                .placeholder(dto.getPlaceholder())
                .regexValidation(dto.getRegexValidation())
                .optionsJson(dto.getOptionsJson())
                .gridWidth(dto.getGridWidth() != null ? dto.getGridWidth() : 6)
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        return mapToDto(dynamicFieldRepo.save(field));
    }

    @Transactional
    public DynamicFieldDto updateField(Long id, DynamicFieldDto dto) {
        access.record(com.leads.model.entity.DynamicFieldDefinition.class, id);
        validateDefinition(dto);
        DynamicFieldDefinition field = dynamicFieldRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Field not found: " + id));

        field.setFieldLabel(dto.getFieldLabel());
        field.setFieldType(dto.getFieldType());
        field.setIsMandatory(Boolean.TRUE.equals(dto.getIsMandatory()));
        field.setDefaultValue(dto.getDefaultValue());
        field.setPlaceholder(dto.getPlaceholder());
        field.setRegexValidation(dto.getRegexValidation());
        field.setOptionsJson(dto.getOptionsJson());
        field.setGridWidth(dto.getGridWidth() == null ? 6 : dto.getGridWidth());
        field.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        field.setIsActive(dto.getIsActive() == null || dto.getIsActive());

        return mapToDto(dynamicFieldRepo.save(field));
    }

    @Transactional
    public void deleteField(Long id) {
        access.record(com.leads.model.entity.DynamicFieldDefinition.class, id);
        dynamicFieldRepo.deleteById(id);
    }

    public void validateDynamicValues(Long companyId, Map<String, Object> values) {
        if (companyId == null) throw new IllegalArgumentException("Company is required.");
        if (values == null) values = Map.of();
        if (values.size() > 100) throw new IllegalArgumentException("Too many dynamic fields.");

        List<DynamicFieldDefinition> definedFields = dynamicFieldRepo.findByCompanyIdAndModuleAndIsActiveTrueOrderBySortOrderAsc(companyId, "LEAD");
        var names = definedFields.stream().map(DynamicFieldDefinition::getFieldName).collect(java.util.stream.Collectors.toSet());
        if (!names.containsAll(values.keySet())) throw new IllegalArgumentException("Unknown or inactive dynamic field.");

        for (DynamicFieldDefinition def : definedFields) {
            Object val = values.get(def.getFieldName());

            com.leads.util.DynamicValueRules.value(def, val);
        }
    }

    private void validateDefinition(DynamicFieldDto dto) {
        if (dto.getFieldType() == null || dto.getFieldName() == null || dto.getFieldLabel() == null
                || dto.getFieldName().isBlank() || dto.getFieldName().length() > 80 || dto.getFieldLabel().isBlank() || dto.getFieldLabel().length() > 120
                || dto.getGridWidth() != null && (dto.getGridWidth() < 1 || dto.getGridWidth() > 12)) {
            throw new IllegalArgumentException("Provide a valid field name, label, type and grid width (1–12).");
        }
        com.leads.util.DynamicValueRules.pattern(dto.getRegexValidation());
        if (com.leads.util.DynamicValueRules.choice(dto.getFieldType())) com.leads.util.DynamicValueRules.options(dto.getOptionsJson());
        if (dto.getFieldType() == com.leads.model.enums.FieldType.FILE) throw new IllegalArgumentException("File fields require a managed upload workflow.");
    }

    private DynamicFieldDto mapToDto(DynamicFieldDefinition entity) {
        return DynamicFieldDto.builder()
                .id(entity.getId())
                .companyId(entity.getCompany().getId())
                .module(entity.getModule())
                .fieldName(entity.getFieldName())
                .fieldLabel(entity.getFieldLabel())
                .fieldType(entity.getFieldType())
                .isMandatory(entity.getIsMandatory())
                .defaultValue(entity.getDefaultValue())
                .placeholder(entity.getPlaceholder())
                .regexValidation(entity.getRegexValidation())
                .optionsJson(entity.getOptionsJson())
                .gridWidth(entity.getGridWidth())
                .sortOrder(entity.getSortOrder())
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
