package com.leads.dto.delivery;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryMarkNotDeliveredRequest {
    @NotNull(message = "A failure reason is required")
    private Long failureReasonId;

    private String failureNotes;
}
