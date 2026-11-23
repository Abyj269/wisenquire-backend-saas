package com.leads.repository;

import com.leads.model.entity.WhatsAppMessageTranscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppMessageTranscriptionRepository extends JpaRepository<WhatsAppMessageTranscription, Long> {
    Optional<WhatsAppMessageTranscription> findByMessageId(Long messageId);
}
