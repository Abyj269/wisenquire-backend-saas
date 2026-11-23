package com.leads.dto.reports;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerReportDto {
    private int customerLostAfterDays;

    private long newlyAcquiredCount;
    private List<CustomerSummaryDto> newlyAcquired;

    private long regainedCount;
    private List<CustomerSummaryDto> regained;

    private long lostCount;
    private List<CustomerSummaryDto> lost;

    private List<TrendPointDto> trend;
}
