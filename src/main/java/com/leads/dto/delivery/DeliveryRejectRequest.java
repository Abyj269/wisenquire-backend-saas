package com.leads.dto.delivery;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRejectRequest {
    @NotBlank(message = "A rejection note is required")
    private String approvalRejectionNotes;
}
