package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.AdProvider;
import com.leads.model.enums.AdSyncStatus;
import com.leads.util.AdCredentialEncryptor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A connected Meta Ads or Google Ads account. {@code branch} is nullable by
 * design — NULL means this is the company-wide default connection for
 * {@code provider}, shared by every branch that has no override; a non-null
 * branch means this row is that one branch's dedicated override. Uniqueness
 * ("at most one company-default and one branch-override per provider") is
 * enforced in AdAccountConnectionService at save time, not by a DB
 * constraint — MySQL/MariaDB unique indexes treat NULL as distinct from
 * NULL, so a composite unique index here would not actually stop two
 * company-default rows for the same provider.
 * <p>
 * Meta uses a single long-lived System User access token as-is. Google Ads
 * has no equivalent static token — it requires OAuth2, so a connection needs
 * the extra developer token / client id+secret / refresh token / (optional)
 * MCC login-customer-id fields instead. Both sets of fields are nullable and
 * gated by {@code provider} rather than split into subclasses, mirroring how
 * WhatsAppConfiguration keeps every credential field flat on one entity.
 */
@Entity
@Table(name = "ad_account_connections", indexes = {
        @Index(name = "idx_ad_conn_company_branch_provider", columnList = "company_id, branch_id, provider")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdAccountConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    // NULL = company-wide default. Non-null = this branch's override.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    @JsonIgnore
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdProvider provider;

    // Meta: "act_123456789". Google: the customer id, digit-only (hyphens
    // stripped at save time — Google's UI displays them dashed, its API does
    // not accept dashes).
    @Column(nullable = false, length = 100)
    private String externalAccountId;

    @Column(length = 150)
    private String displayName;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    // ==================== Meta-only ====================

    @Convert(converter = AdCredentialEncryptor.class)
    @Column(length = 700)
    private String accessToken;

    @Column(length = 100)
    private String appId;

    // ==================== Google-only ====================

    @Convert(converter = AdCredentialEncryptor.class)
    @Column(length = 700)
    private String googleDeveloperToken;

    @Column(length = 255)
    private String googleClientId;

    @Convert(converter = AdCredentialEncryptor.class)
    @Column(length = 700)
    private String googleClientSecret;

    @Convert(converter = AdCredentialEncryptor.class)
    @Column(length = 700)
    private String googleRefreshToken;

    // Digit-only, for MCC/manager-account setups (login-customer-id header).
    @Column(length = 50)
    private String googleLoginCustomerId;

    // ==================== Sync status ====================

    private LocalDateTime lastSyncAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdSyncStatus lastSyncStatus = AdSyncStatus.NEVER_SYNCED;

    @Column(length = 500)
    private String lastSyncError;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
