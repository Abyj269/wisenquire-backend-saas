package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.CustomerStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Company-wide customer/contact master, keyed by mobile number. A customer
 * is NOT locked to one branch — see PhoneNumberUtil / CustomerService for
 * the dedup rule and the multi-branch design (homeBranch is first-touch
 * attribution only; a customer can and does accumulate leads across several
 * branches under this one record).
 */
@Entity
@Table(name = "customers", indexes = {
        @Index(name = "idx_customer_company_mobile", columnList = "company_id, mobile_normalized", unique = true),
        @Index(name = "idx_customer_company_branch", columnList = "company_id, home_branch_id"),
        @Index(name = "idx_customer_company_status", columnList = "company_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    /** First-touch attribution — the branch of this customer's very first lead. Never overwritten later. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_branch_id")
    private Branch homeBranch;

    /** First-touch attribution — the source of this customer's very first lead. Never overwritten later. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initial_source_id")
    private LeadSource initialSource;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String mobileNumber;

    /** Complete international digits; legacy-prefixed keys require exact-number reconciliation. */
    @Column(name = "mobile_normalized", nullable = false, length = 50)
    private String mobileNormalized;

    @Column(length = 50)
    private String whatsappNumber;

    @Column(length = 150)
    private String email;

    @Column(length = 150)
    private String customerCompanyName;

    @Column(length = 100)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Builder.Default
    @Column(nullable = false)
    private Integer totalLeadsCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer convertedLeadsCount = 0;

    private LocalDateTime firstLeadAt;

    private LocalDateTime lastLeadAt;

    private LocalDateTime lastConvertedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // Blocking hides this customer's WhatsApp conversation from the inbox
    // list entirely (see WhatsAppCloudService.buildConversationSpecification)
    // - inbound messages are still stored, just not surfaced, so unblocking
    // restores full history rather than losing anything.
    @Builder.Default
    @Column(name = "is_blocked", nullable = false)
    private Boolean isBlocked = false;

    private LocalDateTime blockedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_by_user_id")
    private User blockedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
