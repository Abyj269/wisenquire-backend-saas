package com.leads.dto.lead;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadProductDto {
    private Long id;

    @NotNull(message = "Product ID is required")
    private Long productId;

    private String productCode;
    private String productName;
    private String sku;

    @Builder.Default
    private Integer quantity = 1;

    private BigDecimal unitPrice;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalPrice;
    private String notes;
}
