package com.leads.dto.ai;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTranslationDto {
    private Long messageId;
    private String translatedText;
    private String sourceLanguage;
    private LocalDateTime generatedAt;
}
