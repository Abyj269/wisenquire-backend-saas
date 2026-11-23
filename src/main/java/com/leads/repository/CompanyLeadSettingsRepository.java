package com.leads.repository;

import com.leads.model.entity.CompanyLeadSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyLeadSettingsRepository extends JpaRepository<CompanyLeadSettings, Long> {
    Optional<CompanyLeadSettings> findByCompanyId(Long companyId);
}
