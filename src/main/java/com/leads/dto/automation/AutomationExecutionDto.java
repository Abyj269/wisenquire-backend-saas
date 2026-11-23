package com.leads.dto.automation;

import com.leads.model.enums.ExecutionStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecutionDto {
    private Long id;
    private Long automationId;
    private String automationName;
    private Long customerId;
    private String customerPhone;
    private String customerName;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String failureReason;
    private List<AutomationExecutionStepDto> steps;
}
