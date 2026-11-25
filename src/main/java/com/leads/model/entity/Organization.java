package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display name. Not unique since V65 (F-6): two tenants may share a name; the slug is unique. */
    @Column(nullable = false, length = 150)
    private String name;

    /** Module 03 (V65): unique, URL-safe, generated from the code, and stable once set. */
    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(length = 50)
    private String subscriptionPlan;

    /** Tenant lifecycle (V51). Enforced in TenantAccess.company() via TenantStatusPolicy. */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private com.leads.model.enums.OrganizationStatus status = com.leads.model.enums.OrganizationStatus.ACTIVE;

    /** When status last changed; the PAST_DUE grace period is measured from it. */
    private LocalDateTime statusChangedAt;

    private LocalDateTime trialEndsAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    private LocalDateTime deletedAt;

    // LONGTEXT, not TEXT — TEXT's 64KB cap a base64-encoded logo easily exceeds.
    @Column(columnDefinition = "LONGTEXT")
    private String logoUrl;

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Company> companies = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Fallback for inserts that do not set a slug (fixtures, seed): the code, slugified. */
    @PrePersist
    void defaultSlug() {
        if (slug == null || slug.isBlank()) slug = com.leads.service.OrganizationSlugs.slugify(code);
    }
}
