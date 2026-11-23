package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.AiProviderType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One stored AI provider credential for a branch. A branch may hold several
 * (one per provider, {@code UNIQUE(branch_id, provider)} — unlike
 * {@link WhatsAppConfiguration}, there's no reason to allow duplicates per
 * provider here), but only the one with {@code isSelected=true} is actually
 * used — a prompt is never fanned out to more than one vendor.
 */
@Entity
@Table(name = "ai_provider_configs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ai_provider_configs_branch_provider", columnNames = {"branch_id", "provider"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    @JsonIgnore
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProviderType provider;

    // Free text, not an enum — new model ids ship from vendors constantly and
    // must never require a migration (e.g. "gpt-4o-mini", "claude-3-5-sonnet-20241022").
    @Column(nullable = false, length = 100)
    private String modelName;

    @Convert(converter = com.leads.util.AiCredentialEncryptor.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    @JsonIgnore
    private String apiKey;

    // Azure OpenAI-compatible endpoints, corporate LLM gateways, self-hosted
    // proxies — null uses each provider client's own hardcoded default base URL.
    @Column(length = 255)
    private String apiBaseUrlOverride;

    // Optional extra scoping id some providers need (OpenAI project/org id,
    // GCP project for Gemini, etc.) — deliberately generic rather than one
    // column per provider, mirroring how WhatsAppConfiguration keeps
    // provider-specific fields flat rather than subclassed.
    @Column(length = 150)
    private String providerAccountRef;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    // Only one row per branch should carry true — enforced in
    // AiProviderConfigService (deactivating the selected row clears this),
    // not by a DB constraint.
    @Builder.Default
    @Column(nullable = false)
    private Boolean isSelected = false;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
