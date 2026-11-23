package com.leads.dto.automation;

import com.leads.model.enums.AutomationStatus;
import com.leads.model.enums.TriggerType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationListDto {
    private Long id;
    private Long companyId;
    private Long branchId;
    private String branchName;
    private String name;
    private String description;
    private AutomationStatus status;
    private TriggerType triggerType;
    private String triggerJson;
    private String conditionsJson;
    private String actionsJson;
    private Integer priority;
    private Boolean stopOnMatch;
    private Integer triggerCount;
    private Integer successCount;
    private Integer failureCount;
    private LocalDateTime lastTriggeredAt;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
