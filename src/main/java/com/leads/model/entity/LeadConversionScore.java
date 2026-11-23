package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Cached AI conversion-strength score for a lead — current value only, not a
 * history table. Refresh is manual/on-demand only (never a scheduled batch
 * job, by explicit product decision), so every refresh also logs a
 * {@link LeadTimelineActivity} (reusing existing infra instead of a new
 * history table) recording who triggered it and the resulting score.
 */
@Entity
@Table(name = "lead_conversion_scores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadConversionScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false, unique = true)
    @JsonIgnore
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Column(nullable = false)
    private Integer score;

    /** VERY_LOW / LOW / MEDIUM / HIGH / VERY_HIGH */
    @Column(nullable = false, length = 20)
    private String strengthLabel;

    /** LOW / MEDIUM / HIGH — piggybacked from the same scoring call, no extra AI request. */
    @Column(length = 20)
    private String urgencyLabel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String rationale;

    /** Structured breakdown from the same AI response (responsiveness, budget mentions, objections, buying-intent phrases) — cheap to store since it's already in the response. */
    @Column(columnDefinition = "TEXT")
    private String signalsJson;

    @Column(nullable = false)
    private Integer basedOnMessageCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_conversation_id")
    @JsonIgnore
    private WhatsAppConversation basedOnConversation;

    @Column(length = 20)
    private String provider;

    @Column(length = 100)
    private String modelName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_user_id", nullable = false)
    private User generatedByUser;

    private LocalDateTime generatedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
