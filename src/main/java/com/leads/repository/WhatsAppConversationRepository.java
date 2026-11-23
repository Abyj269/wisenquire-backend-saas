package com.leads.repository;

import com.leads.model.entity.WhatsAppConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WhatsAppConversationRepository extends JpaRepository<WhatsAppConversation, Long>, JpaSpecificationExecutor<WhatsAppConversation> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WhatsAppConversation c WHERE c.id=:id")
    Optional<WhatsAppConversation> findForSending(@Param("id") Long id);

    // JOIN FETCH lead/assignedAgent here since both are LAZY on the entity —
    // these two methods back the inbox list screen, so without the fetch
    // join every conversation would trigger 2 extra SELECTs (N+1) once
    // mapped to DTOs.
    @Query("SELECT c FROM WhatsAppConversation c LEFT JOIN FETCH c.lead LEFT JOIN FETCH c.assignedAgent LEFT JOIN FETCH c.lastMessageSentBy " +
            "WHERE c.company.id = :companyId ORDER BY c.lastMessageAt DESC")
    List<WhatsAppConversation> findByCompanyIdOrderByLastMessageAtDesc(@Param("companyId") Long companyId);

    @Query("SELECT c FROM WhatsAppConversation c LEFT JOIN FETCH c.lead LEFT JOIN FETCH c.assignedAgent " +
            "WHERE c.receivingPhoneNumberId = :receivingPhoneNumberId ORDER BY c.lastMessageAt DESC")
    List<WhatsAppConversation> findByReceivingPhoneNumberIdOrderByLastMessageAtDesc(@Param("receivingPhoneNumberId") String receivingPhoneNumberId);

    // Bulk-hydrates lead/assignedAgent (both LAZY) for a page of ids already
    // resolved via a Specification+Pageable query — that query can't carry
    // its own fetch joins (they'd apply to the COUNT query Spring Data
    // derives from the same Specification too), so the inbox list's search/
    // filter/pagination service does it as this second, cheap, N+1-free step.
    @Query("SELECT c FROM WhatsAppConversation c LEFT JOIN FETCH c.lead l LEFT JOIN FETCH l.purpose LEFT JOIN FETCH l.status LEFT JOIN FETCH c.assignedAgent LEFT JOIN FETCH c.lastMessageSentBy WHERE c.id IN :ids")
    List<WhatsAppConversation> findByIdInWithLeadAndAgent(@Param("ids") List<Long> ids);

    List<WhatsAppConversation> findByBranchIdOrderByLastMessageAtDesc(Long branchId);
    Optional<WhatsAppConversation> findByCompanyIdAndCustomerWaIdAndReceivingPhoneNumberId(Long companyId, String customerWaId, String receivingPhoneNumberId);
    Optional<WhatsAppConversation> findByLeadId(Long leadId);

    // Backs the Ads module's lead-to-ad reconciliation sweep: conversations
    // carrying a genuine ad referral whose external ad id was just synced,
    // but whose linked lead hasn't resolved an Ad yet (either because the ad
    // didn't exist in our DB at lead-creation time, or the lead was created
    // by a path that doesn't attempt the lookup). JOIN FETCH avoids an N+1
    // on lead/lead.branch, both LAZY, which AdAttributionResolver needs.
    @Query("SELECT c FROM WhatsAppConversation c JOIN FETCH c.lead l LEFT JOIN FETCH l.branch " +
            "WHERE c.company.id = :companyId AND c.adSourceId IN :adSourceIds AND l.ad IS NULL")
    List<WhatsAppConversation> findPendingAdAttribution(@Param("companyId") Long companyId, @Param("adSourceIds") List<String> adSourceIds);

    // --- Filter pill counts (see ConversationCountsDto) — company/number-wide, independent of search or the active pill. ---

    @Query("SELECT COUNT(c) FROM WhatsAppConversation c WHERE c.company.id = :companyId AND (:phoneNumberId IS NULL OR c.receivingPhoneNumberId = :phoneNumberId)")
    long countAll(@Param("companyId") Long companyId, @Param("phoneNumberId") String phoneNumberId);

    @Query("SELECT COUNT(c) FROM WhatsAppConversation c WHERE c.company.id = :companyId AND (:phoneNumberId IS NULL OR c.receivingPhoneNumberId = :phoneNumberId) AND (c.unreadCount > 0 OR c.manuallyUnread = true)")
    long countUnread(@Param("companyId") Long companyId, @Param("phoneNumberId") String phoneNumberId);

    // "Active" mirrors the inbox badge/ring: excludes conversion flag and
    // CONVERTED, LOST and CLOSED stages.
    @Query("SELECT COUNT(c) FROM WhatsAppConversation c WHERE c.company.id = :companyId AND (:phoneNumberId IS NULL OR c.receivingPhoneNumberId = :phoneNumberId) " +
            "AND c.lead IS NOT NULL AND c.lead.isConverted = false AND c.lead.status.stageType NOT IN (com.leads.model.enums.StageType.CONVERTED, com.leads.model.enums.StageType.LOST, com.leads.model.enums.StageType.CLOSED)")
    long countActiveLeads(@Param("companyId") Long companyId, @Param("phoneNumberId") String phoneNumberId);

    @Query("SELECT COUNT(c) FROM WhatsAppConversation c WHERE c.company.id = :companyId AND (:phoneNumberId IS NULL OR c.receivingPhoneNumberId = :phoneNumberId) " +
            "AND c.lead IS NOT NULL AND c.lead.assignedTo.id = :userId")
    long countAssignedToMe(@Param("companyId") Long companyId, @Param("phoneNumberId") String phoneNumberId, @Param("userId") Long userId);
}
