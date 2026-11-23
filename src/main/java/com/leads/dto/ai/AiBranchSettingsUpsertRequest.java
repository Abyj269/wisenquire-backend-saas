package com.leads.dto.ai;

import lombok.*;

/** Every field optional — only the fields an admin actually changed need to be sent; anything null leaves the current (or default) value untouched. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiBranchSettingsUpsertRequest {
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
