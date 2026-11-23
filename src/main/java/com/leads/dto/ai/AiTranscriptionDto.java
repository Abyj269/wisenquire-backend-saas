package com.leads.dto.ai;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTranscriptionDto {
    private Long messageId;
    private String transcriptText;
}
