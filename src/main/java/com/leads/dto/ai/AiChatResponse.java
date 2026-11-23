package com.leads.dto.ai;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiChatResponse {
    private String content;
    /** Nullable defensively, though all 4 providers report usage in practice. */
    private Integer promptTokens;
    private Integer completionTokens;
    /** Normalized across providers: "stop" | "length" | "content_filter" | "error". */
    private String finishReason;
}
