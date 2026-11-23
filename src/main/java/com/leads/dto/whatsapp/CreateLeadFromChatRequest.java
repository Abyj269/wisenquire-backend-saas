package com.leads.dto.whatsapp;

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
public class CreateLeadFromChatRequest {
    @NotNull(message = "Conversation ID is required")
    private Long conversationId;

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

    /** Explicit source pick — omit to keep the default WHATSAPP/WHATSAPP_MARKETING auto-source this endpoint has always used. */
    private Long sourceId;
    private Long purposeId;
    private Long statusId;
    private Long assignedToUserId;
    private BigDecimal estimatedDealValue;
    private LocalDate closingDate;
    private String notes;

    private Map<String, Object> dynamicValues;
    private List<Long> productIds;
}
