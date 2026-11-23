package com.leads.repository;

import com.leads.model.entity.WhatsAppMessage;
import com.leads.model.enums.MessageDirection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {
    // Backed by idx_msg_conversation_created (conversation_id, created_at, id)
    // — stays fast regardless of how large the table grows, since it never
    // scans more than one conversation's rows. Sorting by id as a tiebreaker
    // after created_at keeps pagination deterministic when multiple messages
    // land on the same timestamp (e.g. a fast webhook burst).
    Page<WhatsAppMessage> findByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId, Pageable pageable);

    Optional<WhatsAppMessage> findByWamid(String wamid);

    // Backs AiContextAssemblyService: the most recent messages within a
    // lookback window, most-recent-first (same idx_msg_conversation_created
    // index as the paginated history query above) — the service reverses
    // this to chronological order itself after applying its char-budget trim.
    @Query("SELECT m FROM WhatsAppMessage m WHERE m.conversation.id = :conversationId AND m.createdAt >= :since ORDER BY m.createdAt DESC, m.id DESC")
    List<WhatsAppMessage> findRecentForContext(@Param("conversationId") Long conversationId, @Param("since") LocalDateTime since, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<WhatsAppMessage> findByConversationIdAndClientMessageId(Long conversationId, String clientMessageId);

    // Staff Performance report: per-agent outbound WhatsApp volume for a
    // company/branch/date window. Object[] rows are [userId (Long), count (Long)].
    @Query("SELECT m.sentByUser.id, COUNT(m) FROM WhatsAppMessage m " +
            "WHERE m.conversation.company.id = :companyId " +
            "AND (:branchId IS NULL OR m.conversation.branch.id = :branchId) " +
            "AND m.direction = 'OUTBOUND' AND m.sentByUser IS NOT NULL " +
            "AND (:fromDate IS NULL OR m.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR m.createdAt < :toDate) " +
            "GROUP BY m.sentByUser.id")
    List<Object[]> countOutboundByUser(@Param("companyId") Long companyId, @Param("branchId") Long branchId,
                                        @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // Template health dashboard: per-template send counts by status for one
    // connected number within an optional date range. Object[] rows are
    // [templateName (String), status (MessageStatus), count (Long)].
    @Query("SELECT m.templateName, m.status, COUNT(m) FROM WhatsAppMessage m " +
            "WHERE m.conversation.receivingPhoneNumberId = :phoneNumberId " +
            "AND m.direction = 'OUTBOUND' AND m.messageType = 'TEMPLATE' AND m.templateName IS NOT NULL " +
            "AND (:fromDate IS NULL OR m.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR m.createdAt < :toDate) " +
            "GROUP BY m.templateName, m.status")
    List<Object[]> countTemplateUsage(@Param("phoneNumberId") String phoneNumberId,
                                       @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    long countByConversationIdAndDirection(Long conversationId, MessageDirection direction);
}
