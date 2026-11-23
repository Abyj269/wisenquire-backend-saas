package com.leads.repository;

import com.leads.model.entity.PredefinedFieldConfig;
import com.leads.model.enums.PredefinedLeadField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PredefinedFieldConfigRepository extends JpaRepository<PredefinedFieldConfig, Long> {
    List<PredefinedFieldConfig> findByCompanyId(Long companyId);
    Optional<PredefinedFieldConfig> findByCompanyIdAndFieldKey(Long companyId, PredefinedLeadField fieldKey);
}
