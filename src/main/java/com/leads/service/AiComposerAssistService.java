package com.leads.service;

import com.leads.dto.ai.*;
import com.leads.model.entity.WhatsAppConversation;
import com.leads.model.enums.AiChatRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The two composer-facing AI actions: suggest a reply to the customer's
 * latest message, or improve an agent's own typed draft for clarity/
 * professionalism. Both populate the composer textarea only — the agent
 * still reviews/edits and clicks the existing Send button; neither ever
 * sends a message itself.
 */
@Service
@RequiredArgsConstructor
public class AiComposerAssistService {
    private final com.leads.flags.FeatureFlags flags;
    private final AiMeteredCalls meteredCalls;

    private static final int MAX_OUTPUT_TOKENS = 400;

    private final AiContextAssemblyService contextAssembly;
    private final AiUsageGuardService usageGuard;
    private final AiProviderResolver providerResolver;

    public AiTextResultDto suggestReply(Long conversationId) {
        AssembledAiContext ctx = contextAssembly.assembleForConversation(conversationId);
        if (ctx.getMessages().isEmpty()) {
            throw new IllegalArgumentException("No chat history available to suggest a reply from.");
        }
        Long branchId = requireBranchId(ctx.getConversation());

        String systemPrompt = AiContextAssemblyService.PROMPT_INJECTION_GUARD + "\n\n"
                + "You are helping a customer-service agent at a business reply to a customer over WhatsApp. "
                + "Read the conversation and draft the agent's next reply: professional, warm, concise, in the "
                + "same language the customer is writing in, directly addressing their most recent message. "
                + "Reply with ONLY the message text — no preamble, no quotation marks, no explanation.";

        AiTextResultDto result = complete(branchId, AiUsageGuardService.Feature.REPLY_SUGGESTION, systemPrompt, ctx);
        return AiTextResultDto.builder()
                .text(result.getText())
                .basedOnMessageCount(ctx.getMessageCount())
                .provider(result.getProvider())
                .modelName(result.getModelName())
                .build();
    }

    public AiTextResultDto improveDraft(Long conversationId, String draftText) {
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("Type a draft message first.");
        }
        AssembledAiContext ctx = contextAssembly.assembleForConversation(conversationId);
        Long branchId = requireBranchId(ctx.getConversation());

        String systemPrompt = AiContextAssemblyService.PROMPT_INJECTION_GUARD + "\n\n"
                + "You are helping a customer-service agent at a business polish a WhatsApp reply they've already "
                + "drafted, using the conversation only as context for tone and topic. Improve the DRAFT below for "
                + "clarity, professionalism and grammar while preserving its original meaning, intent and language — "
                + "do not add new claims, promises or information it didn't already contain. "
                + "Reply with ONLY the improved message text — no preamble, no quotation marks, no explanation.\n\n"
                + "DRAFT:\n" + draftText;

        AiTextResultDto result = complete(branchId, AiUsageGuardService.Feature.MESSAGE_IMPROVEMENT, systemPrompt, ctx);
        return AiTextResultDto.builder()
                .text(result.getText())
                .basedOnMessageCount(ctx.getMessageCount())
                .provider(result.getProvider())
                .modelName(result.getModelName())
                .build();
    }

    private AiTextResultDto complete(Long branchId, AiUsageGuardService.Feature feature, String systemPrompt, AssembledAiContext ctx) {
        int estimatedPromptChars = systemPrompt.length();
        for (AiChatMessage m : ctx.getMessages()) estimatedPromptChars += m.getContent().length();

        AiUsageGuardService.Reservation reservation = usageGuard.checkAndReserve(branchId, feature, estimatedPromptChars, MAX_OUTPUT_TOKENS);
        // Module 18 P1 (S3-b): with ai.short_transactions a failed call gives its reservation back (as the other AI features).
        boolean giveBackOnFailure = flags.isOn(com.leads.flags.FeatureFlag.AI_SHORT_TRANSACTIONS);
        try {
        AiProviderResolver.Resolved resolved = providerResolver.resolveForBranch(branchId);

        // improveDraft folds the draft into the system prompt (not a trailing
        // USER message) so the model treats it as "the thing to edit", not
        // "the newest customer turn to reply to" — ctx.getMessages() here is
        // purely conversational context either way.
        List<AiChatMessage> messages = ctx.getMessages().isEmpty()
                ? List.of(AiChatMessage.builder().role(AiChatRole.USER).content("(No prior messages.)").build())
                : ctx.getMessages();

        var response = meteredCalls.chat(feature.name(), resolved.getClient(), resolved.getConfig(), com.leads.dto.ai.AiChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(messages)
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.6)
                .build());

        usageGuard.recordActual(reservation, response.getPromptTokens(), response.getCompletionTokens());

        return AiTextResultDto.builder()
                .text(response.getContent() != null ? response.getContent().trim() : "")
                .provider(resolved.getConfig().getProvider().name())
                .modelName(resolved.getConfig().getModelName())
                .build();
        } catch (RuntimeException e) {
            if (giveBackOnFailure) usageGuard.releaseNow(reservation);
            throw e;
        }
    }

    private Long requireBranchId(WhatsAppConversation conversation) {
        if (conversation.getBranch() == null) {
            throw new IllegalStateException("This conversation has no branch assigned; AI features require a branch.");
        }
        return conversation.getBranch().getId();
    }
}
