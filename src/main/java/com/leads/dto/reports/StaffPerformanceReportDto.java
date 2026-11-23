package com.leads.dto.reports;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffPerformanceReportDto {
    private List<StaffPerformanceDto> staff;
    private double teamAverageScore;
    private String topPerformerName;
}
