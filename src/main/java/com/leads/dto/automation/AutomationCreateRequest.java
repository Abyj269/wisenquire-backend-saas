package com.leads.dto.automation;

import com.leads.model.enums.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationCreateRequest {
    @NotNull(message = "Company ID is required")
    private Long companyId;

    private Long branchId;

    @NotBlank(message = "Automation name is required")
    private String name;

    private String description;

    // No status field on purpose: lifecycle changes only go through the
    // publish/pause endpoints, which run publish-time validation and require
    // their own permission.

    @NotNull(message = "Trigger type is required")
    private TriggerType triggerType;

    private String triggerJson;
    private String conditionsJson;
    private String actionsJson;

    private Integer priority;
    private Boolean stopOnMatch;
}
