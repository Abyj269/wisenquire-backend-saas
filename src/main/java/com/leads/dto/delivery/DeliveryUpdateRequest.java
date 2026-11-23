package com.leads.dto.delivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryUpdateRequest {
    @NotNull(message = "A scheduled date is required")
    private LocalDate scheduledDate;

    private Long assignedToUserId;

    @NotBlank(message = "Recipient name is required")
    private String recipientName;

    @NotBlank(message = "Recipient phone is required")
    private String recipientPhone;

    @NotBlank(message = "Address line 1 is required")
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
}
