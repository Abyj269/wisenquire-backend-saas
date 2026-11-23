package com.leads.dto.quotation;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationItemDto {
    private Long id;
    private Long productId;

    @NotBlank(message = "Item name is required")
    private String itemName;

    private String itemDescription;

    @Builder.Default
    private Integer quantity = 1;

    private BigDecimal unitPrice;

    @Builder.Default
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal taxPercent = BigDecimal.ZERO;

    // Server-computed — any value sent by the client here is ignored and recalculated.
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;
}
