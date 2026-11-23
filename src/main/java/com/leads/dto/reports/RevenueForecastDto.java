package com.leads.dto.reports;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueForecastDto {
    private List<StageValueDto> byStage;
    private BigDecimal totalPipelineValue;
    private BigDecimal wonRevenue;
    private BigDecimal openPipelineValue;
    private double companyConversionRate;
    /** Sum of each open stage's value weighted by the period's overall conversion rate — a simple, explainable v1 forecast, not a stage-by-stage probability model. */
    private BigDecimal forecastedRevenue;
}
