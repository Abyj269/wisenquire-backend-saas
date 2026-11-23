package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One ad's spend/impressions/clicks for one calendar day. Daily grain (not
 * lifetime totals, not a per-sync snapshot) is what makes both "spend until
 * now" ({@code SUM(spend)}, no date filter) and "spend in an arbitrary date
 * range" ({@code SUM(spend) WHERE stat_date BETWEEN :from AND :to}) the same
 * trivial query, and what makes every sync/backfill/re-run a safe upsert on
 * {@code (ad_id, stat_date)} instead of risking double-counted spend.
 * <p>
 * {@code currency} is the ad account's own currency, stored as-is — there is
 * deliberately no FX conversion to Company.currency here (see the ad
 * performance report's currency-mismatch caveat).
 */
@Entity
@Table(name = "ad_insights", indexes = {
        @Index(name = "idx_ad_insights_ad_date", columnList = "ad_id, stat_date", unique = true),
        @Index(name = "idx_ad_insights_date", columnList = "stat_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ad_id", nullable = false)
    @JsonIgnore
    private Ad ad;

    @Column(nullable = false)
    private LocalDate statDate;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(length = 10)
    private String currency;

    private Long impressions;

    private Long clicks;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
