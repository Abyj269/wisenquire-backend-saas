package com.leads.repository;

import com.leads.model.entity.WhatsAppAutoReplySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppAutoReplySettingsRepository extends JpaRepository<WhatsAppAutoReplySettings, Long> {
    Optional<WhatsAppAutoReplySettings> findByCompanyId(Long companyId);
}
