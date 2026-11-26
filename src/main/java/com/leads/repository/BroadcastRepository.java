package com.leads.repository;

import com.leads.model.entity.Broadcast;
import com.leads.model.enums.BroadcastStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BroadcastRepository extends JpaRepository<Broadcast, Long>, JpaSpecificationExecutor<Broadcast> {

    Page<Broadcast> findByCompanyIdAndDeletedAtIsNull(Long companyId, Pageable pageable);

    Page<Broadcast> findByCompanyIdAndBranchIdAndDeletedAtIsNull(Long companyId, Long branchId, Pageable pageable);

    /** F-1: list for BRANCH-level users who did not pick a branch — only their permitted branches. */
    Page<Broadcast> findByCompanyIdAndBranchIdInAndDeletedAtIsNull(Long companyId, java.util.Collection<Long> branchIds, Pageable pageable);

    Optional<Broadcast> findByIdAndCompanyIdAndDeletedAtIsNull(Long id, Long companyId);

    List<Broadcast> findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(BroadcastStatus status, LocalDateTime now);

    /**
     * Eagerly fetches whatsappConfiguration and company alongside the broadcast.
     * Used by the background worker thread (executeBroadcastSend) to avoid
     * LazyInitializationException when accessing lazy associations outside the
     * original HTTP request Hibernate session.
     */
    @Query("SELECT b FROM Broadcast b " +
           "LEFT JOIN FETCH b.whatsappConfiguration " +
           "LEFT JOIN FETCH b.company " +
           "LEFT JOIN FETCH b.branch " +
           "WHERE b.id = :id")
    Optional<Broadcast> findByIdWithConfig(@Param("id") Long id);

    /**
     * Used ONLY by BroadcastSchedulerService. Returns scalar (broadcastId, companyId)
     * pairs for due SCHEDULED broadcasts — no Hibernate proxies, no lazy associations,
     * safe to read on a non-transactional scheduler thread.
     *
     * The companyId is needed so the scheduler can call TenantAccess.inCompany(companyId)
     * to establish a system-level security context before calling sendBroadcast().
     */
    @Query("SELECT b.id, b.company.id FROM Broadcast b " +
           "WHERE b.status = :status " +
           "AND b.scheduledAt <= :now " +
           "AND b.deletedAt IS NULL")
    List<Object[]> findDueScheduledBroadcastIdAndCompanyId(
            @Param("status") BroadcastStatus status,
            @Param("now") LocalDateTime now);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Broadcast b SET b.deliveredCount = b.deliveredCount + 1 WHERE b.id = :id")
    void incrementDeliveredCount(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Broadcast b SET b.readCount = b.readCount + 1 WHERE b.id = :id")
    void incrementReadCount(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Broadcast b SET b.failedCount = b.failedCount + 1 WHERE b.id = :id")
    void incrementFailedCount(@Param("id") Long id);

    // ---- module 16 P1: chunked sender and recovery ----

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Broadcast b SET b.sentCount = b.sentCount + 1 WHERE b.id = :id")
    void incrementSentCount(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Broadcast b SET b.lastHeartbeatAt = :now WHERE b.id = :id")
    void touchHeartbeat(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** [broadcastId, companyId] of SENDING broadcasts with a heartbeat older than the cutoff (chunked path only). */
    @Query("SELECT b.id, b.company.id FROM Broadcast b WHERE b.status = com.leads.model.enums.BroadcastStatus.SENDING "
            + "AND b.deletedAt IS NULL AND b.lastHeartbeatAt IS NOT NULL AND b.lastHeartbeatAt < :cutoff")
    List<Object[]> findStalledSending(@Param("cutoff") LocalDateTime cutoff);
}
