package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Company-configurable lead source master (Direct, WhatsApp, Website,
 * Instagram, ...) — mirrors LeadPurpose/LeadStatus's pattern exactly. The
 * six sources seeded by {@code LeadSettingsService#seedDefaultSources} are
 * isSystemDefined(true): they can never be renamed or deleted, only added
 * to; anything a company adds afterward through Settings is fully
 * editable and deletable like a Purpose is.
 */
@Entity
@Table(name = "lead_sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false, length = 100)
    private String sourceName;

    @Column(nullable = false, length = 50)
    private String sourceCode;

    /** Lucide icon name (e.g. "message-square") rendered next to the source everywhere it appears. */
    @Column(length = 50)
    private String icon;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String colorCode = "#6366F1";

    @Builder.Default
    @Column(nullable = false)
    private Integer sortOrder = 0;

    /** True for the company's seeded lead sources — those can never be renamed, recolored, or deleted; only a source a company adds later can be. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean isSystemDefined = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
