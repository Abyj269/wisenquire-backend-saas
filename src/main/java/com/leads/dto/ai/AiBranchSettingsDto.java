package com.leads.dto.ai;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiBranchSettingsDto {
    private Long branchId;
    private Integer contextMaxMessages;
    private Integer contextMaxLookbackHours;
    private Integer contextMaxChars;
    private Integer dailyRequestCap;
    private Integer monthlyRequestCap;
    private Long monthlyTokenCap;
    private Integer notifyThresholdPercent;
    private Boolean replySuggestionEnabled;
    private Boolean messageImprovementEnabled;
    private Boolean translationEnabled;
    private Boolean conversionScoringEnabled;
}
