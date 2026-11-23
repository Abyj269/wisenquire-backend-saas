package com.leads.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductMasterDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Product code is required")
    private String productCode;

    @NotBlank(message = "Product name is required")
    private String productName;

    private String sku;
    private String category;

    @NotNull(message = "Unit price is required")
    private BigDecimal unitPrice;

    private String currency;
    private BigDecimal taxPercentage;
    private String description;
    private Boolean isActive;
}
