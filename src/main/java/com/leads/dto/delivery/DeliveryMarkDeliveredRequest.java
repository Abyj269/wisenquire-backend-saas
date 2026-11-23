package com.leads.dto.delivery;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryMarkDeliveredRequest {
    private String deliveryProofNotes;
}
