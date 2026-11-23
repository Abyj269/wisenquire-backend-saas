package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false, unique = true, length = 50)
    private String leadNumber; // e.g. "LD-2026-0001"

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    @Column(length = 150)
    private String email;

    @Column(nullable = false, length = 50)
    private String phone;

    @Column(length = 50)
    private String whatsappNumber;

    @Column(length = 150)
    private String customerCompanyName;

    @Column(length = 100)
    private String jobTitle;

    @Column(length = 2)
    private String nationality; // ISO2 country code, e.g. "AE" — fixed option list, not admin-editable

    @Column(length = 5)
    private String preferredLanguage; // "EN" | "AR" | "FR" | "DE" — fixed option list, not admin-editable

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id", nullable = false)
    private LeadSource source;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "purpose_id")
    private LeadPurpose purpose;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "status_id", nullable = false)
    private LeadStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    @Column(precision = 14, scale = 2)
    private BigDecimal estimatedDealValue;

    private LocalDate closingDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // Declared JSON at the DB level (native binary JSON on MySQL 8; MariaDB
    // treats it as a LONGTEXT + CHECK-constraint alias — either way it's a
    // plain String on the Java side. No @JdbcTypeCode(SqlTypes.JSON): that
    // forces Hibernate to expect a literal Types.JSON back from schema
    // validation, which MariaDB's JDBC driver never reports (it reports
    // LONGVARCHAR), causing a false-positive validation failure on startup.
    @Column(columnDefinition = "json")
    private String dynamicValues; // JSON payload storing dynamic custom field values: {"budget_range":"$10k-$50k", "pref_lang":"English"}

    @Builder.Default
    @Column(nullable = false)
    private Boolean isConverted = false;

    private LocalDateTime convertedAt;

    // Which synced Ad this lead is attributed to, when resolvable. Nullable
    // and best-effort: a WhatsApp CTWA click can become a lead within
    // seconds, long before the next periodic ad sync has ever heard of that
    // ad id — see AdSyncConnectionService's reconciliation sweep, which
    // fills this in after the fact for leads whose ad_source_id didn't
    // resolve at creation time.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ad_id")
    private Ad ad;

    // Meta's click-to-WhatsApp click id, copied from the originating
    // WhatsAppConversation at lead-creation time (known immediately, unlike
    // `ad`, which depends on ad-sync timing). Not used anywhere yet — kept
    // for a future Conversions API (CAPI) offline-event upload, which
    // matches on this value.
    @Column(length = 255)
    private String ctwaClid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lost_reason_id")
    private LeadLostReason lostReason;

    @Column(columnDefinition = "TEXT")
    private String lostNotes;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LeadProduct> products = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    @Builder.Default
    @JsonIgnore
    private List<LeadTimelineActivity> activities = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public String getFullName() {
        return (firstName + (lastName != null ? " " + lastName : "")).trim();
    }
}
