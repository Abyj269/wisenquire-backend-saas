package com.leads.dto.reports;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffPerformanceDto {
    private Long userId;
    private String name;
    private long leadsAssigned;
    private long leadsConverted;
    private double conversionRate;
    private Double avgResponseMinutes; // null when there's no measurable first-response activity yet
    private long whatsappMessagesSent;
    /** Composite 0-100 score — see ReportsService.computeStaffScore for the weighting. */
    private double score;
    private int rank;
}
