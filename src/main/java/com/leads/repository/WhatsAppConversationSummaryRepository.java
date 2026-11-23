package com.leads.repository;

import com.leads.model.entity.WhatsAppConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppConversationSummaryRepository extends JpaRepository<WhatsAppConversationSummary, Long> {
    Optional<WhatsAppConversationSummary> findByConversationId(Long conversationId);
}
