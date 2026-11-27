package com.leads.repository;

import com.leads.model.entity.AiUsageCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface AiUsageCounterRepository extends JpaRepository<AiUsageCounter, Long> {

    // Atomic upsert, same INSERT ... ON DUPLICATE KEY UPDATE shape as
    // DocumentNumberService uses for document_sequences — the pre-call
    // admission reservation. estimatedTokens is the pre-call estimate
    // (recordActual reconciles it to the provider's real reported usage
    // afterward via applyTokenDelta).
    @Modifying
    @Query(value = "INSERT INTO ai_usage_counters(branch_id, company_id, period_type, period_key, request_count, estimated_tokens, updated_at) " +
            "VALUES (:branchId, :companyId, :periodType, :periodKey, 1, :estimatedTokens, NOW()) " +
            "ON DUPLICATE KEY UPDATE request_count = request_count + 1, estimated_tokens = estimated_tokens + VALUES(estimated_tokens), updated_at = NOW()",
            nativeQuery = true)
    void upsertIncrement(@Param("branchId") Long branchId, @Param("companyId") Long companyId,
                          @Param("periodType") String periodType, @Param("periodKey") String periodKey,
                          @Param("estimatedTokens") long estimatedTokens);

    // Row-locked read right after upsertIncrement, in the same transaction —
    // so the admission decision and any concurrent request for the same
    // branch/period serialize correctly, same FOR UPDATE pattern
    // DocumentNumberService uses for document_sequences.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AiUsageCounter c WHERE c.branchId = :branchId AND c.periodType = :periodType AND c.periodKey = :periodKey")
    Optional<AiUsageCounter> findForUpdate(@Param("branchId") Long branchId, @Param("periodType") String periodType, @Param("periodKey") String periodKey);

    Optional<AiUsageCounter> findByBranchIdAndPeriodTypeAndPeriodKey(Long branchId, String periodType, String periodKey);

    /** Module 18 P1: gives back one admitted request and its estimated tokens (the provider call failed). */
    @Modifying
    @Query(value = "UPDATE ai_usage_counters SET request_count = GREATEST(request_count - 1, 0), "
            + "estimated_tokens = GREATEST(estimated_tokens - :tokens, 0), updated_at = NOW() "
            + "WHERE branch_id = :branchId AND period_type = :periodType AND period_key = :periodKey", nativeQuery = true)
    void release(@Param("branchId") Long branchId, @Param("periodType") String periodType,
                 @Param("periodKey") String periodKey, @Param("tokens") long tokens);

    // Post-call reconciliation: nudges estimated_tokens toward the provider's
    // real reported usage instead of permanently drifting from the cheap
    // pre-call char/4 estimate. A no-op (delta 0) if the row was since purged.
    @Modifying
    @Query(value = "UPDATE ai_usage_counters SET estimated_tokens = estimated_tokens + :delta, updated_at = NOW() " +
            "WHERE branch_id = :branchId AND period_type = :periodType AND period_key = :periodKey", nativeQuery = true)
    void applyTokenDelta(@Param("branchId") Long branchId, @Param("periodType") String periodType,
                         @Param("periodKey") String periodKey, @Param("delta") long delta);
}
