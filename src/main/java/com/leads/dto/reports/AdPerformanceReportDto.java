package com.leads.dto.reports;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdPerformanceReportDto {
    private List<AdPerformanceRowDto> byAd;
    private List<CampaignPerformanceRowDto> byCampaign;
    private BigDecimal totalSpend;
    private long totalLeads;
    private long totalConverted;
    private BigDecimal totalRevenue;
    // True when at least one row in byAd/byCampaign is shared company-wide
    // (branchExclusive=false) rather than exclusive to the filtered branch —
    // the UI shows a caveat banner when this is true and a branch is selected.
    private boolean anySharedAccountSpend;
}
