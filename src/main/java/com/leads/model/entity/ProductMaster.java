package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_masters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 50)
    private String productCode;

    @Column(nullable = false, length = 150)
    private String productName;

    @Column(length = 50)
    private String sku;

    @Column(length = 100)
    private String category;

    @Builder.Default
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(length = 10)
    private String currency = "USD";

    @Builder.Default
    @Column(precision = 5, scale = 2)
    private BigDecimal taxPercentage = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
