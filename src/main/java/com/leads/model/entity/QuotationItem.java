package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "quotation_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    @JsonIgnore
    private Quotation quotation;

    /** Nullable — an ad-hoc line with no catalog entry is a normal part of a quotation. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id")
    private ProductMaster product;

    @Column(nullable = false, length = 200)
    private String itemName;

    @Column(columnDefinition = "TEXT")
    private String itemDescription;

    @Builder.Default
    @Column(nullable = false)
    private Integer quantity = 1;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false)
    private Integer sortOrder = 0;
}
