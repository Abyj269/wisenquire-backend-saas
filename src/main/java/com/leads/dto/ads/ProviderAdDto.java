package com.leads.dto.ads;

import lombok.*;

/** Provider-agnostic shape one ad/campaign row is normalized into, whether it came from Meta's Graph API or Google's Ads API. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderAdDto {
    private String externalAdId;
    private String externalCampaignId;
    private String externalAdsetId;
    private String adName;
    private String campaignName;
    private String adsetName;
    private String status; // raw provider status string — normalized to AdStatus by the caller
    private String objective;
}
