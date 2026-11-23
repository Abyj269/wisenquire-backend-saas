package com.leads.dto.ai;

import lombok.*;

import java.util.List;

/**
 * Provider-agnostic chat-completion request. Each {@link com.leads.service.AiChatProvider}
 * implementation maps this onto its own vendor-specific shape (see the class
 * javadoc on {@code AiChatProvider} for the real per-provider differences).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiChatRequest {
    /** Always passed separately from the message list — never folded in as a "system"-role message, since Anthropic/Gemini reject or ignore that. */
    private String systemPrompt;
    private List<AiChatMessage> messages;
    private Integer maxOutputTokens;
    /** Nullable -> provider default. */
    private Double temperature;
    /** True for conversion scoring (structured result); every other feature leaves this false. No provider is assumed to guarantee strict JSON mode — callers still parse leniently. */
    @Builder.Default
    private boolean expectJson = false;
}
