package com.leads.dto.delivery;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAssignRequest {
    @NotNull(message = "Delivery IDs are required")
    private List<Long> deliveryIds;

    @NotNull(message = "Assign to User ID is required")
    private Long assignToUserId;

    private String assignmentNotes;
}
