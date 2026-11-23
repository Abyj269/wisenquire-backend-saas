package com.leads.dto.delivery;

import com.leads.dto.customer.CustomerSummaryDto;
import com.leads.dto.lead.LeadProductDto;
import com.leads.model.enums.DeliveryStatus;
import com.leads.model.enums.RequestedDeliveryAction;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryDto {
    private Long id;
    private Long organizationId;
    private Long companyId;
    private Long branchId;
    private String branchName;

    private Long leadId;
    private String leadNumber;
    private String leadFullName;
    private String leadPhone;
    private CustomerSummaryDto customer;
    private List<LeadProductDto> products;

    private String deliveryNumber;
    private DeliveryStatus status;
    private LocalDate scheduledDate;

    private Long assignedToUserId;
    private String assignedToName;
    private Long assignedByUserId;
    private String assignedByName;

    private String recipientName;
    private String recipientPhone;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateOrEmirate;
    private String country;
    private String postalCode;
    private String landmark;
    private java.math.BigDecimal latitude;
    private java.math.BigDecimal longitude;
    private String deliveryNotes;

    private Integer attemptCount;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime deliveredAt;
    private String deliveryProofNotes;

    private Long failureReasonId;
    private String failureReasonText;
    private String failureNotes;

    private RequestedDeliveryAction requestedAction;
    private LocalDate requestedNewDate;
    private Long requestedByUserId;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private String requestNotes;

    private Long approvalDecisionByUserId;
    private String approvalDecisionByName;
    private LocalDateTime approvalDecisionAt;
    private String approvalRejectionNotes;

    private String cancelledReason;
    private Long cancelledByUserId;
    private String cancelledByName;
    private LocalDateTime cancelledAt;

    private Long createdByUserId;
    private String createdByName;

    /** Advisory only — true once attemptCount reaches the company's maxDeliveryAttempts, never a hard block. */
    private Boolean attemptsExceededAdvisory;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
