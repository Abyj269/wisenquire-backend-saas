package com.leads.dto.reports;

import com.leads.model.enums.AdProvider;
import com.leads.model.enums.AdStatus;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdPerformanceRowDto {
    private Long adId;
    private AdProvider provider;
    private String adName;
    private String campaignName;
    private AdStatus status;
    private BigDecimal spend;
    private String currency;
    private long leadsCount;
    private long convertedCount;
    private double conversionRate;
    // null when spend is zero (division by zero) rather than a misleading 0.
    private BigDecimal costPerLead;
    private BigDecimal revenue;
    private Double roas;
    // false when this ad belongs to the company-wide default connection —
    // i.e. its spend is shared across every branch with no override, not
    // exclusive to the branch this report row is being shown under.
    private boolean branchExclusive;
}
