package com.leads.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Durable, atomically-incremented AI usage counter, one row per
 * (branch, periodType, periodKey). Modeled directly on {@code document_sequences}
 * / {@link com.leads.service.DocumentNumberService} — deliberately NOT
 * {@link com.leads.service.RequestRateLimiter}, which is in-memory, resets on
 * restart, and explicitly not safe across multiple app instances.
 * <p>
 * Never fetched by its own id via a REST path — always addressed by
 * branchId (already tenant-checked at the call site), hence plain BIGINT
 * columns rather than JPA relationships, and no {@code TenantAccess.record()}
 * case (the same reasoning as why {@code document_sequences} itself has none).
 */
@Entity
@Table(name = "ai_usage_counters", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ai_usage_counters_branch_period", columnNames = {"branch_id", "period_type", "period_key"})
})
public class AiUsageCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** "DAY" or "MONTH". */
    @Column(nullable = false, length = 10)
    private String periodType;

    /** "YYYY-MM-DD" for DAY, "YYYY-MM" for MONTH — computed in company-local time. */
    @Column(nullable = false, length = 10)
    private String periodKey;

    @Column(nullable = false)
    private Long requestCount;

    @Column(nullable = false)
    private Long estimatedTokens;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Long getBranchId() { return branchId; }
    public Long getCompanyId() { return companyId; }
    public String getPeriodType() { return periodType; }
    public String getPeriodKey() { return periodKey; }
    public Long getRequestCount() { return requestCount; }
    public Long getEstimatedTokens() { return estimatedTokens; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
