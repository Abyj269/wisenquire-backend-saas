package com.leads.dto.quotation;

import com.leads.model.enums.QuotationStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationDto {
    private Long id;
    private Long leadId;
    private String leadNumber;
    private String leadName;
    private Long companyId;
    private String quotationNumber;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String customerCompanyName;
    private String customerTaxNumber;
    private String customerAddress;
    private String notes;
    private String termsAndConditions;
    private BigDecimal subtotal;
    private BigDecimal totalDiscount;
    private BigDecimal totalTax;
    private BigDecimal grandTotal;
    private String currency;
    private QuotationStatus status;
    private String createdByName;
    private List<QuotationItemDto> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
