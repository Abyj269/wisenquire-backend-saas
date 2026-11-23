package com.leads.dto.ads;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One ad's spend/impressions/clicks for one calendar day, normalized across providers. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderInsightDto {
    private String externalAdId;
    private LocalDate statDate;
    private BigDecimal spend;
    private String currency;
    private Long impressions;
    private Long clicks;
}
