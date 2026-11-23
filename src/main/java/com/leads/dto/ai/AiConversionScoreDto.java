package com.leads.dto.ai;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversionScoreDto {
    private Long leadId;
    private String leadNumber;
    private Integer score;
    private String strengthLabel;
    private String urgencyLabel;
    private String rationale;
    /** Raw JSON object from the AI response (responsiveness, budget mentions, objections, buying-intent phrases) — advisory detail, rendered as-is. */
    private String signalsJson;
    private Integer basedOnMessageCount;
    private String generatedByUserName;
    private LocalDateTime generatedAt;
}
