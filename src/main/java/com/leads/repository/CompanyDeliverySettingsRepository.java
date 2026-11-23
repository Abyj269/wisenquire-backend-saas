package com.leads.repository;

import com.leads.model.entity.CompanyDeliverySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyDeliverySettingsRepository extends JpaRepository<CompanyDeliverySettings, Long> {
    Optional<CompanyDeliverySettings> findByCompanyId(Long companyId);
}
