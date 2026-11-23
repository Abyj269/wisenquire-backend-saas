package com.leads.repository;

import com.leads.model.entity.DynamicFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DynamicFieldDefinitionRepository extends JpaRepository<DynamicFieldDefinition, Long> {
    List<DynamicFieldDefinition> findByCompanyIdAndModuleOrderBySortOrderAsc(Long companyId, String module);
    List<DynamicFieldDefinition> findByCompanyIdAndModuleAndIsActiveTrueOrderBySortOrderAsc(Long companyId, String module);
    Optional<DynamicFieldDefinition> findByCompanyIdAndFieldName(Long companyId, String fieldName);
}
