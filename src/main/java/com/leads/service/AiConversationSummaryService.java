package com.leads.service;

import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.dto.ai.AiTextResultDto;
import com.leads.dto.ai.AssembledAiContext;
import com.leads.model.entity.WhatsAppConversation;
import com.leads.model.entity.WhatsAppConversationSummary;
import com.leads.repository.WhatsAppConversationSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * On-demand AI summary of a chat thread, for an agent catching up on a long
 * conversation (e.g. after a reassignment). Reuses the entire context-
 * assembly + usage-guard + provider-abstraction stack unchanged — same shape
 * as conversion scoring minus JSON parsing. Refreshable, unlike the
 * translation cache: a conversation's content keeps growing.
 */
@Service
@RequiredArgsConstructor
public class AiConversationSummaryService {
    private final AiMeteredCalls meteredCalls;

    private static final int MAX_OUTPUT_TOKENS = 300;
    private static final String SYSTEM_PROMPT_TEMPLATE = AiContextAssemblyService.PROMPT_INJECTION_GUARD + "\n\n"
            + "Summarize the WhatsApp conversation below in 2-3 concise sentences for an agent who is new to it or "
            + "catching up: what the customer wants, key facts (product interest, budget, timeline), and where "
            + "things currently stand. Reply with ONLY the summary — no preamble.";

    private final TenantAccess access;
    private final AiContextAssemblyService contextAssembly;
    private final AiUsageGuardService usageGuard;
    private final AiProviderResolver providerResolver;
    private final WhatsAppConversationSummaryRepository summaryRepo;

    @Transactional
    public AiTextResultDto summarize(Long conversationId) {
        AssembledAiContext ctx = contextAssembly.assembleForConversation(conversationId);
        if (ctx.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Not enough chat history yet to summarize.");
        }
        WhatsAppConversation conversation = ctx.getConversation();
        Long branchId = conversation.getBranch().getId();

        int estimatedPromptChars = SYSTEM_PROMPT_TEMPLATE.length();
        for (AiChatMessage m : ctx.getMessages()) estimatedPromptChars += m.getContent().length();

        AiUsageGuardService.Reservation reservation = usageGuard.checkAndReserve(
                branchId, AiUsageGuardService.Feature.SUMMARIZATION, estimatedPromptChars, MAX_OUTPUT_TOKENS);
        AiProviderResolver.Resolved resolved = providerResolver.resolveForBranch(branchId);

        AiChatResponse response = meteredCalls.chat("SUMMARIZATION", resolved.getClient(), resolved.getConfig(), AiChatRequest.builder()
                .systemPrompt(SYSTEM_PROMPT_TEMPLATE)
                .messages(ctx.getMessages())
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.4)
                .build());

        usageGuard.recordActual(reservation, response.getPromptTokens(), response.getCompletionTokens());
        String summaryText = response.getContent() != null ? response.getContent().trim() : "";

        WhatsAppConversationSummary summary = summaryRepo.findByConversationId(conversationId)
                .orElseGet(WhatsAppConversationSummary::new);
        summary.setConversation(conversation);
        summary.setCompany(conversation.getCompany());
        summary.setSummaryText(summaryText);
        summary.setBasedOnMessageCount(ctx.getMessageCount());
        summary.setProvider(resolved.getConfig().getProvider().name());
        summary.setModelName(resolved.getConfig().getModelName());
        summary.setGeneratedByUser(access.actor());
        summaryRepo.save(summary);

        return AiTextResultDto.builder()
                .text(summaryText)
                .basedOnMessageCount(ctx.getMessageCount())
                .provider(resolved.getConfig().getProvider().name())
                .modelName(resolved.getConfig().getModelName())
                .build();
    }
}
