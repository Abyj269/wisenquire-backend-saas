package com.leads.dto.lead;

import jakarta.validation.constraints.NotBlank;
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
public class LeadUpdateRequest {
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
    private String nationality;
    private String preferredLanguage;
    private Long sourceId;
    private Long purposeId;
    private Long statusId;
    private Long assignedToUserId;
    private BigDecimal estimatedDealValue;
    private LocalDate closingDate;
    private String notes;
    private Map<String, Object> dynamicValues;
    private List<LeadProductDto> products;
}
