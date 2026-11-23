package com.leads.dto.automation;

import com.leads.model.enums.ExecutionStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecutionStepDto {
    private Long id;
    private Long executionId;
    private String nodeType;
    private String nodeLabel;
    private ExecutionStatus status;
    private LocalDateTime executedAt;
    private String detail;
}
