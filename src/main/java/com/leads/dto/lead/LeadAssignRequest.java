package com.leads.dto.lead;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadAssignRequest {
    @NotNull(message = "Lead IDs are required")
    private List<Long> leadIds;

    @NotNull(message = "Assign to User ID is required")
    private Long assignToUserId;

    private String assignmentNotes;
}
