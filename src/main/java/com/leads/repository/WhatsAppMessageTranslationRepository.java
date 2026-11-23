package com.leads.repository;

import com.leads.model.entity.WhatsAppMessageTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppMessageTranslationRepository extends JpaRepository<WhatsAppMessageTranslation, Long> {
    Optional<WhatsAppMessageTranslation> findByMessageId(Long messageId);
}
