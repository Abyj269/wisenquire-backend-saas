package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "lead_products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    @JsonIgnore
    private Lead lead;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductMaster product;

    @Builder.Default
    @Column(nullable = false)
    private Integer quantity = 1;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(precision = 14, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    @Column(length = 255)
    private String notes;
}
