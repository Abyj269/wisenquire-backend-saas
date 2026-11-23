package com.leads.dto.quotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/** Used for both create and update — leadId is required on create and read-only (but still sent) on update. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationRequest {

    @NotNull(message = "Lead ID is required")
    private Long leadId;

    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String customerCompanyName;
    private String customerTaxNumber;
    private String customerAddress;
    /** ISO currency code, e.g. "AED" — defaults to the lead's company currency when blank. */
    private String currency;
    private String notes;
    private String termsAndConditions;

    @NotEmpty(message = "At least one line item is required")
    @Valid
    private List<QuotationItemDto> items;
}
