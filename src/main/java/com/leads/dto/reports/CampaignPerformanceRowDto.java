package com.leads.dto.reports;

import com.leads.model.enums.AdProvider;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignPerformanceRowDto {
    private String externalCampaignId;
    private AdProvider provider;
    private String campaignName;
    private BigDecimal spend;
    private String currency;
    private long leadsCount;
    private long convertedCount;
    private double conversionRate;
    private BigDecimal costPerLead;
    private BigDecimal revenue;
    private Double roas;
    private boolean branchExclusive;
}
