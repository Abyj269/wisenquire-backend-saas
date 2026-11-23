package com.leads.repository;

import com.leads.model.entity.WhatsAppConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WhatsAppConfigurationRepository extends JpaRepository<WhatsAppConfiguration, Long> {
    List<WhatsAppConfiguration> findByCompanyIdOrderByIdAsc(Long companyId);
    List<WhatsAppConfiguration> findByBranchIdOrderByIdAsc(Long branchId);
    Optional<WhatsAppConfiguration> findByPhoneNumberId(String phoneNumberId);
    Optional<WhatsAppConfiguration> findByVerifyToken(String verifyToken);
    List<WhatsAppConfiguration> findByWabaIdAndIsActiveTrue(String wabaId);
}
