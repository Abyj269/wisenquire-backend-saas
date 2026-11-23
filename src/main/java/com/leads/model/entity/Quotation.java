package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.QuotationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A basic price quote against a single lead — one lead can have several
 * quotations over time (re-quoted, revised, etc). Buyer-side fields snapshot
 * the lead/customer's details at creation time but stay independently
 * editable here, so a quotation remains a stable record even if the lead is
 * later edited. Sent to the customer as a generated PDF, either downloaded
 * or pushed straight into the linked WhatsApp conversation.
 */
@Entity
@Table(name = "quotations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quotation {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Column(name = "quotation_number", nullable = false, unique = true, length = 50)
    private String quotationNumber;

    @Column(length = 150)
    private String customerName;

    @Column(length = 50)
    private String customerPhone;

    @Column(length = 150)
    private String customerEmail;

    @Column(length = 150)
    private String customerCompanyName;

    /** Buyer's own tax/VAT registration number — genuinely optional. */
    @Column(length = 50)
    private String customerTaxNumber;

    @Column(length = 255)
    private String customerAddress;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Bulleted Terms & Conditions block on the PDF (one bullet per line) — distinct from the free-text notes above, seeded from Branch.quotationTerms. */
    @Column(columnDefinition = "TEXT")
    private String termsAndConditions;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalTax = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, length = 10)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private QuotationStatus status = QuotationStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<QuotationItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
