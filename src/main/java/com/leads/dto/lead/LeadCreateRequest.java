package com.leads.dto.lead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadCreateRequest {

    @NotNull(message = "Company ID is required")
    private Long companyId;

    @NotNull(message = "Branch ID is required")
    private Long branchId;

    @NotBlank(message = "First name is required")
    private String firstName;

    private String lastName;
    private String email;

    @NotBlank(message = "Phone number is required")
    private String phone;

    private String whatsappNumber;
    private String customerCompanyName;
    private String jobTitle;
    private String nationality; // ISO2 code — validated against a fixed list, mandatory-ness is per-company (see PredefinedFieldService)
    private String preferredLanguage; // "EN" | "AR" | "FR" | "DE"

    /** Optional — defaults to the company's built-in "Direct Lead" source when omitted. */
    private Long sourceId;

    private Long purposeId;
    private Long statusId; // If null, uses company default status
    private Long assignedToUserId;
    private BigDecimal estimatedDealValue;
    private LocalDate closingDate;
    private String notes;

    // Dynamic custom fields defined for this company
    private Map<String, Object> dynamicValues;

    // Optional attached products (if link product enabled)
    private List<LeadProductDto> products;
}
