package com.leads.dto.ai;

import lombok.*;

/** Shared shape for suggest-reply, improve-draft, and conversation-summary results — all a single generated text plus how much history it was based on. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTextResultDto {
    private String text;
    private int basedOnMessageCount;
    private String provider;
    private String modelName;
}
