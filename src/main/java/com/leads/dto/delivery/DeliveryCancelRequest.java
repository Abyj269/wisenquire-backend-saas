package com.leads.dto.delivery;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryCancelRequest {
    @NotBlank(message = "A cancellation reason is required")
    private String cancelledReason;
}
