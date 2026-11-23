package com.leads.dto.lead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadLostRequest {
    @NotNull(message = "Lead ID is required")
    private Long leadId;

    @NotNull(message = "A lost reason is required")
    private Long lostReasonId;

    @NotBlank(message = "A note is required")
    private String notes;
}
