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
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final com.leads.flags.FeatureFlags flags;

    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    private org.springframework.transaction.support.TransactionTemplate readTx() {
        var t = tx();
        t.setReadOnly(true);
        return t;
    }

    /** Module 18 P1 (S3-b): flag ai.short_transactions for the caller's organization. */
    private boolean shortTransactions() {
        return flags.isOn(com.leads.flags.FeatureFlag.AI_SHORT_TRANSACTIONS);
    }

    /**
     * Flag ai.short_transactions OFF: the original single transaction (which holds the branch's usage-counter
     * lock during the provider call). ON: short transactions, nothing open during the provider call.
     */
    public AiTextResultDto summarize(Long conversationId) {
        return shortTransactions() ? summarizeShort(conversationId) : tx().execute(st -> summarizeInOneTransaction(conversationId));
    }

    private AiTextResultDto summarizeShort(Long conversationId) {
        record Prep(AssembledAiContext ctx, Long branchId, int chars) { }
        Prep p = readTx().execute(st -> {
            AssembledAiContext ctx = contextAssembly.assembleForConversation(conversationId);
            if (ctx.getMessages().isEmpty()) throw new IllegalArgumentException("Not enough chat history yet to summarize.");
            int chars = SYSTEM_PROMPT_TEMPLATE.length();
            for (AiChatMessage m : ctx.getMessages()) chars += m.getContent().length();
            return new Prep(ctx, ctx.getConversation().getBranch().getId(), chars);
        });
        AiUsageGuardService.Reservation reservation = usageGuard.reserveNow(
                p.branchId(), AiUsageGuardService.Feature.SUMMARIZATION, p.chars(), MAX_OUTPUT_TOKENS);
        AiProviderResolver.Resolved resolved;
        AiChatResponse response;
        try {
            resolved = readTx().execute(st -> providerResolver.resolveForBranch(p.branchId()));
            AiUsageGuardService.requireNoTransaction();
            response = meteredCalls.chat("SUMMARIZATION", resolved.getClient(), resolved.getConfig(), AiChatRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT_TEMPLATE)
                    .messages(p.ctx().getMessages())
                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                    .temperature(0.4)
                    .build());
        } catch (RuntimeException e) {
            usageGuard.releaseNow(reservation);
            throw e;
        }
        usageGuard.recordActualNow(reservation, response.getPromptTokens(), response.getCompletionTokens());
        String summaryText = response.getContent() != null ? response.getContent().trim() : "";
        String provider = resolved.getConfig().getProvider().name(), model = resolved.getConfig().getModelName();
        tx().executeWithoutResult(st -> {
            WhatsAppConversation conversation = access.record(WhatsAppConversation.class, conversationId);
            WhatsAppConversationSummary summary = summaryRepo.findByConversationId(conversationId).orElseGet(WhatsAppConversationSummary::new);
            summary.setConversation(conversation);
            summary.setCompany(conversation.getCompany());
            summary.setSummaryText(summaryText);
            summary.setBasedOnMessageCount(p.ctx().getMessageCount());
            summary.setProvider(provider);
            summary.setModelName(model);
            summary.setGeneratedByUser(access.actor());
            summaryRepo.save(summary);
        });
        return AiTextResultDto.builder().text(summaryText).basedOnMessageCount(p.ctx().getMessageCount())
                .provider(provider).modelName(model).build();
    }

    /** The original path (flag OFF), unchanged; runs inside the caller's transaction. */
    private AiTextResultDto summarizeInOneTransaction(Long conversationId) {
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
