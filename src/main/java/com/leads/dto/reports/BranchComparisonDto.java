package com.leads.dto.reports;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchComparisonDto {
    private Long branchId;
    private String branchName;
    private long leadsCount;
    private long convertedCount;
    private double conversionRate;
    private BigDecimal revenue;
    private long newCustomersAcquired;
}
