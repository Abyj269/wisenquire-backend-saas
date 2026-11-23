package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Branch-wise context window + usage cap + feature-toggle guardrails for AI
 * calls. Deliberately its own table, one-to-one with {@link Branch}, rather
 * than an extension of {@link CompanyLeadSettings} (company-scoped by
 * design) — a small branch and a large branch legitimately need different
 * caps, and decoupling this from {@link AiProviderConfig} means an admin can
 * tighten a cap without touching credentials.
 * <p>
 * Row creation is lazy (like {@link BranchTrustedIp}) — a branch with no row
 * yet simply uses the same conservative defaults declared here, never
 * "unlimited". Caps are opt-out, not opt-in: this is the single most
 * important correctness detail behind "avoid usage overflow" — a
 * newly-configured branch must never be able to run unbounded before an
 * admin thinks to set a cap.
 */
@Entity
@Table(name = "ai_branch_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiBranchSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false, unique = true)
    @JsonIgnore
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Builder.Default
    @Column(nullable = false)
    private Integer contextMaxMessages = 20;

    @Builder.Default
    @Column(nullable = false)
    private Integer contextMaxLookbackHours = 72;

    @Builder.Default
    @Column(nullable = false)
    private Integer contextMaxChars = 8000;

    @Builder.Default
    @Column(nullable = false)
    private Integer dailyRequestCap = 200;

    @Builder.Default
    @Column(nullable = false)
    private Integer monthlyRequestCap = 3000;

    @Builder.Default
    @Column(nullable = false)
    private Long monthlyTokenCap = 5_000_000L;

    @Builder.Default
    @Column(nullable = false)
    private Integer notifyThresholdPercent = 80;

    @Builder.Default
    @Column(nullable = false)
    private Boolean replySuggestionEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean messageImprovementEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean translationEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean conversionScoringEnabled = true;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
