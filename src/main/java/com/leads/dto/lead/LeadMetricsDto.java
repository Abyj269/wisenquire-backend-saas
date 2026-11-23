package com.leads.dto.lead;

import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadMetricsDto {
    private long totalLeads;
    private long pendingPurposeLeads;
    private long openLeads;
    private long inProgressLeads;
    private long qualifiedLeads;
    private long convertedLeads;
    private long lostLeads;
    private double conversionRate;
    private BigDecimal totalPipelineValue;
    private Map<String, Long> leadsByStatus;
    private Map<String, Long> leadsBySource;
}
