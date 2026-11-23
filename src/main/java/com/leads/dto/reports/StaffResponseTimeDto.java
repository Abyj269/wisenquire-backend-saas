package com.leads.dto.reports;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffResponseTimeDto {
    private Long userId;
    private String name;
    private double avgResponseMinutes;
    private double medianResponseMinutes;
    private double slaCompliancePercent;
    private long leadsResponded;
}
