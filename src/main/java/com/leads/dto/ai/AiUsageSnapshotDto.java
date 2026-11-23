package com.leads.dto.ai;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageSnapshotDto {
    private Long branchId;
    private long dayRequestCount;
    private int dailyRequestCap;
    private long monthRequestCount;
    private int monthlyRequestCap;
    private long monthEstimatedTokens;
    private long monthlyTokenCap;
    private int notifyThresholdPercent;
    /** True once any of the above crosses notifyThresholdPercent — drives the composer's "approaching your branch's AI limit" banner. */
    private boolean approachingLimit;
}
