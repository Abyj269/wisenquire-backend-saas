package com.leads.dto.automation;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationAnalyticsDto {
    private Long automationId;
    private Long totalExecutions;
    private Long successfulExecutions;
    private Long failedExecutions;
    private Long runningExecutions;
    private Double successRate;
    private Long totalMessagesSent;
    private Long totalCustomersReached;
}
