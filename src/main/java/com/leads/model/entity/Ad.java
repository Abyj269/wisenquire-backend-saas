package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.AdProvider;
import com.leads.model.enums.AdStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One ad, synced from a connected Meta or Google Ads account. {@code branch}
 * is a denormalized copy of {@code connection.branch} at sync time (not a
 * live join) purely so the ad-performance report can tell, per row, whether
 * it's looking at a branch-exclusive ad account or one shared company-wide —
 * needed because a company with no branch override shares one account's
 * spend across every branch, and Meta/Google have no way to sub-allocate
 * that spend per branch.
 */
@Entity
@Table(name = "ads", indexes = {
        @Index(name = "idx_ads_connection_external", columnList = "connection_id, external_ad_id", unique = true),
        @Index(name = "idx_ads_company_campaign", columnList = "company_id, external_campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    // Denormalized from connection.branch at sync time — see class Javadoc.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    @JsonIgnore
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    @JsonIgnore
    private AdAccountConnection connection;

    // Denormalized from connection.provider — avoids a join for grouping.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdProvider provider;

    @Column(nullable = false, length = 100)
    private String externalAdId;

    @Column(nullable = false, length = 100)
    private String externalCampaignId;

    @Column(length = 100)
    private String externalAdsetId; // Meta ad set / Google ad group

    @Column(length = 255)
    private String adName;

    @Column(length = 255)
    private String campaignName;

    @Column(length = 255)
    private String adsetName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdStatus status = AdStatus.UNKNOWN;

    @Column(length = 50)
    private String objective;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
