package com.leads.repository;

import com.leads.model.entity.BroadcastRecipient;
import com.leads.model.enums.BroadcastRecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BroadcastRecipientRepository extends JpaRepository<BroadcastRecipient, Long> {

    Page<BroadcastRecipient> findByBroadcastId(Long broadcastId, Pageable pageable);

    Optional<BroadcastRecipient> findByWamid(String wamid);

    List<BroadcastRecipient> findByBroadcastIdAndStatus(Long broadcastId, BroadcastRecipientStatus status);

    long countByBroadcastIdAndStatus(Long broadcastId, BroadcastRecipientStatus status);

    /** Module 16 P1: a recipient only through its own broadcast (the chunked sender never loads one by id alone). */
    Optional<BroadcastRecipient> findByIdAndBroadcastId(Long id, Long broadcastId);

    // ---- module 16 P1: chunked sender and recovery ----

    /** Locks up to {@code limit} PENDING recipients of the broadcast (skipping rows another sender holds). */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM broadcast_recipients WHERE broadcast_id = :broadcastId "
            + "AND status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> lockPendingIds(@org.springframework.data.repository.query.Param("broadcastId") Long broadcastId,
                              @org.springframework.data.repository.query.Param("limit") int limit);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE BroadcastRecipient r SET r.status = :status WHERE r.id IN :ids")
    int markStatus(@org.springframework.data.repository.query.Param("ids") List<Long> ids,
                   @org.springframework.data.repository.query.Param("status") BroadcastRecipientStatus status);

    /** Step 5.22: one claimed recipient's Meta call starts (only from SENDING_ITEM). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE BroadcastRecipient r SET r.status = com.leads.model.enums.BroadcastRecipientStatus.ATTEMPTING "
            + "WHERE r.id = :id AND r.broadcast.id = :broadcastId AND r.status = com.leads.model.enums.BroadcastRecipientStatus.SENDING_ITEM")
    int markAttempting(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("broadcastId") Long broadcastId);

    /** Step 5.22 (at most once): an attempt the sender never recorded becomes UNCONFIRMED and is not resent. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE BroadcastRecipient r SET r.status = com.leads.model.enums.BroadcastRecipientStatus.UNCONFIRMED, "
            + "r.errorMessage = :reason WHERE r.broadcast.id = :broadcastId AND r.status = com.leads.model.enums.BroadcastRecipientStatus.ATTEMPTING AND r.wamid IS NULL")
    int markUnconfirmedAttempts(@org.springframework.data.repository.query.Param("broadcastId") Long broadcastId,
                                @org.springframework.data.repository.query.Param("reason") String reason);

    /** Recovery: claimed recipients whose Meta call never started (no wamid) go back to PENDING. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE BroadcastRecipient r SET r.status = com.leads.model.enums.BroadcastRecipientStatus.PENDING "
            + "WHERE r.broadcast.id = :broadcastId AND r.status = com.leads.model.enums.BroadcastRecipientStatus.SENDING_ITEM AND r.wamid IS NULL")
    int resetUnsentClaims(@org.springframework.data.repository.query.Param("broadcastId") Long broadcastId);
}
