package com.leads.dto.reports;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadSourceRoiDto {
    private String sourceCode;
    private String sourceName;
    private String colorCode;
    private long leadsCount;
    private long convertedCount;
    private double conversionRate;
    private BigDecimal revenue;
}
