package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "branches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 100)
    private String city;

    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    // Optional per-branch toggle: when true, AuthService.enforceIpAllowlist()
    // requires a non-trusted user's login IP to match one of this branch's
    // BranchTrustedIp entries (or be denied). When false (the default), no IP
    // check happens at all for this branch, regardless of any IPs configured.
    @Builder.Default
    @Column(nullable = false)
    private Boolean ipRestrictionEnabled = false;

    /** Seeds a new quotation's currency for a lead at this branch — falls back to the company's currency when unset. Always overridable per-quotation. */
    @Column(length = 10)
    private String defaultCurrency;

    /** Seeds a new custom line item's VAT % for a quotation against a lead at this branch. Always overridable per-quotation/line. */
    @Column(precision = 5, scale = 2)
    private java.math.BigDecimal defaultVatPercent;

    // --- Quotation PDF letterhead (all optional) ---

    /** Branch-specific logo for the quotation PDF, stored as a base64 data URI like Company.logoUrl — falls back to the company's logo when unset. LONGTEXT: TEXT's 64KB cap is too small for a base64-encoded image. */
    @Column(columnDefinition = "LONGTEXT")
    private String logoUrl;

    @Column(length = 255)
    private String address;

    /** Falls back to the company's own taxNumber on the PDF when unset — separate branches can be separate legal entities with their own TRN. */
    @Column(length = 50)
    private String trnNumber;

    /** Seeds a new quotation's Notes/Terms field for a lead at this branch. Always overridable per-quotation. */
    @Column(columnDefinition = "TEXT")
    private String quotationTerms;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
