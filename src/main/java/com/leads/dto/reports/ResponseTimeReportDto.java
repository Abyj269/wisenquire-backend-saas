package com.leads.dto.reports;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResponseTimeReportDto {
    /** Minutes considered "on time" for a first response — fixed v1 benchmark, not yet a configurable setting. */
    private int slaTargetMinutes;
    private double overallAvgResponseMinutes;
    private double overallMedianResponseMinutes;
    private double slaCompliancePercent;
    private List<StaffResponseTimeDto> byStaff;
}
