package com.leads.dto.delivery;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryCloseRequest {
    @NotBlank(message = "A note explaining why the delivery is being closed is required")
    private String requestNotes;
}
