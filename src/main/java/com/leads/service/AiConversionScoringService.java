package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.dto.ai.AiConversionScoreDto;
import com.leads.dto.ai.AssembledAiContext;
import com.leads.exception.AiProviderApiException;
import com.leads.model.entity.Lead;
import com.leads.model.entity.LeadConversionScore;
import com.leads.model.entity.WhatsAppConversation;
import com.leads.model.enums.ActivityType;
import com.leads.repository.LeadConversionScoreRepository;
import com.leads.repository.LeadRepository;
import com.leads.repository.WhatsAppConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * AI-derived conversion-strength score for a lead, computed from its linked
 * WhatsApp conversation. On-demand + manual refresh ONLY — never a scheduled
 * batch job (explicit product decision) — so this service is the single most
 * expensive of the AI features per call (full bounded context, not one
 * message) and is deliberately not wired to run automatically anywhere.
 * Piggybacks an urgency label onto the same response (zero extra AI calls).
 */
@Service
@RequiredArgsConstructor
public class AiConversionScoringService {
    private final AiMeteredCalls meteredCalls;

    private static final int MAX_OUTPUT_TOKENS = 600;
    private static final String SCORING_INSTRUCTIONS =
            "You are analyzing a WhatsApp sales conversation between a customer and a business agent to assess "
            + "how likely the customer is to convert (complete a purchase / close the deal). Consider "
            + "responsiveness, expressed intent, budget/timeline mentions, and objections raised.\n\n"
            + "Respond with ONLY a single JSON object, no markdown fences, no explanation, in exactly this shape:\n"
            + "{\"score\": <integer 0-100>, \"strengthLabel\": \"VERY_LOW|LOW|MEDIUM|HIGH|VERY_HIGH\", "
            + "\"urgencyLabel\": \"LOW|MEDIUM|HIGH\", \"rationale\": \"<2-4 sentence explanation>\", "
            + "\"signals\": {\"responsiveness\": \"<short phrase>\", \"budgetMentioned\": <true|false>, "
            + "\"objections\": [\"...\"], \"buyingIntentPhrases\": [\"...\"]}}";
    private static final String JSON_RETRY_REMINDER = "\n\nIMPORTANT: Your last response was not valid JSON. Return ONLY the JSON object described above — nothing else.";

    private final TenantAccess access;
    private final LeadRepository leadRepo;
    private final WhatsAppConversationRepository conversationRepo;
    private final LeadConversionScoreRepository scoreRepo;
    private final AiContextAssemblyService contextAssembly;
    private final AiUsageGuardService usageGuard;
    private final AiProviderResolver providerResolver;
    private final LeadService leadService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<AiConversionScoreDto> getCached(String leadNumber) {
        Lead lead = requireLead(leadNumber);
        return scoreRepo.findByLeadId(lead.getId()).map(s -> toDto(lead, s));
    }

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

    public AiConversionScoreDto refresh(String leadNumber) {
        return shortTransactions() ? refreshShort(leadNumber) : tx().execute(st -> refreshInOneTransaction(leadNumber));
    }

    /** Module 18 P1 (S3-b): short transactions; nothing is open during either provider call (the JSON retry included). */
    private AiConversionScoreDto refreshShort(String leadNumber) {
        record Prep(Long leadId, Long conversationId, Long branchId, AssembledAiContext ctx) { }
        Prep p = readTx().execute(st -> {
            Lead lead = requireLead(leadNumber);
            WhatsAppConversation conversation = conversationRepo.findByLeadId(lead.getId())
                    .orElseThrow(() -> new IllegalArgumentException("This lead has no linked WhatsApp conversation to analyze."));
            AssembledAiContext ctx = contextAssembly.assembleForConversation(conversation.getId());
            if (ctx.getMessages().isEmpty()) throw new IllegalArgumentException("Not enough chat history yet to score this lead.");
            return new Prep(lead.getId(), conversation.getId(), conversation.getBranch().getId(), ctx);
        });
        JsonNode parsed = attemptScoringCallShort(p.branchId(), p.ctx(), SCORING_INSTRUCTIONS);
        if (parsed == null) parsed = attemptScoringCallShort(p.branchId(), p.ctx(), SCORING_INSTRUCTIONS + JSON_RETRY_REMINDER);
        if (parsed == null) throw new AiProviderApiException("The AI provider returned an unparseable response for conversion scoring. Please try again.");
        final JsonNode result = parsed;
        return tx().execute(st -> {
            Lead lead = requireLead(leadNumber);
            WhatsAppConversation conversation = conversationRepo.findByLeadId(lead.getId())
                    .orElseThrow(() -> new IllegalArgumentException("This lead has no linked WhatsApp conversation to analyze."));
            LeadConversionScore score = scoreRepo.findByLeadId(lead.getId()).orElseGet(() ->
                    LeadConversionScore.builder().lead(lead).company(lead.getCompany()).build());
            score.setScore(result.path("score").asInt(0));
            score.setStrengthLabel(result.path("strengthLabel").asText("MEDIUM"));
            score.setUrgencyLabel(result.path("urgencyLabel").asText(null));
            score.setRationale(result.path("rationale").asText(""));
            score.setSignalsJson(result.has("signals") ? result.get("signals").toString() : null);
            score.setBasedOnMessageCount(p.ctx().getMessageCount());
            score.setBasedOnConversation(conversation);
            score.setGeneratedByUser(access.actor());
            score.setGeneratedAt(LocalDateTime.now());
            LeadConversionScore saved = scoreRepo.save(score);
            leadService.recordActivity(lead, ActivityType.NOTE, "AI Conversion Score Refreshed",
                    "Score: " + saved.getScore() + "/100 (" + saved.getStrengthLabel() + "), urgency "
                            + saved.getUrgencyLabel() + " — based on the last " + saved.getBasedOnMessageCount() + " messages.",
                    access.actor());
            return toDto(lead, saved);
        });
    }

    private JsonNode attemptScoringCallShort(Long branchId, AssembledAiContext ctx, String systemPrompt) {
        int estimatedPromptChars = systemPrompt.length();
        for (AiChatMessage m : ctx.getMessages()) estimatedPromptChars += m.getContent().length();
        AiUsageGuardService.Reservation reservation = usageGuard.reserveNow(
                branchId, AiUsageGuardService.Feature.CONVERSION_SCORING, estimatedPromptChars, MAX_OUTPUT_TOKENS);
        AiChatResponse response;
        try {
            AiProviderResolver.Resolved resolved = readTx().execute(st -> providerResolver.resolveForBranch(branchId));
            AiUsageGuardService.requireNoTransaction();
            response = meteredCalls.chat("CONVERSION_SCORING", resolved.getClient(), resolved.getConfig(), AiChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .messages(ctx.getMessages())
                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                    .temperature(0.3)
                    .expectJson(true)
                    .build());
        } catch (RuntimeException e) {
            usageGuard.releaseNow(reservation);
            throw e;
        }
        usageGuard.recordActualNow(reservation, response.getPromptTokens(), response.getCompletionTokens());
        return tryParseJson(response.getContent());
    }

    /** The original path (flag OFF), unchanged; runs inside the caller's transaction. */
    private AiConversionScoreDto refreshInOneTransaction(String leadNumber) {
        Lead lead = requireLead(leadNumber);
        WhatsAppConversation conversation = conversationRepo.findByLeadId(lead.getId())
                .orElseThrow(() -> new IllegalArgumentException("This lead has no linked WhatsApp conversation to analyze."));

        AssembledAiContext ctx = contextAssembly.assembleForConversation(conversation.getId());
        if (ctx.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Not enough chat history yet to score this lead.");
        }
        Long branchId = conversation.getBranch().getId();

        JsonNode parsed = completeWithJsonRetry(branchId, ctx, SCORING_INSTRUCTIONS);

        LeadConversionScore score = scoreRepo.findByLeadId(lead.getId()).orElseGet(() ->
                LeadConversionScore.builder().lead(lead).company(lead.getCompany()).build());
        score.setScore(parsed.path("score").asInt(0));
        score.setStrengthLabel(parsed.path("strengthLabel").asText("MEDIUM"));
        score.setUrgencyLabel(parsed.path("urgencyLabel").asText(null));
        score.setRationale(parsed.path("rationale").asText(""));
        score.setSignalsJson(parsed.has("signals") ? parsed.get("signals").toString() : null);
        score.setBasedOnMessageCount(ctx.getMessageCount());
        score.setBasedOnConversation(conversation);
        score.setGeneratedByUser(access.actor());
        score.setGeneratedAt(LocalDateTime.now());

        LeadConversionScore saved = scoreRepo.save(score);

        leadService.recordActivity(lead, ActivityType.NOTE, "AI Conversion Score Refreshed",
                "Score: " + saved.getScore() + "/100 (" + saved.getStrengthLabel() + "), urgency "
                        + saved.getUrgencyLabel() + " — based on the last " + saved.getBasedOnMessageCount() + " messages.",
                access.actor());

        return toDto(lead, saved);
    }

    /** Up to 2 real provider calls (each independently metered against the branch's usage cap) — one retry if the first response isn't valid JSON. */
    private JsonNode completeWithJsonRetry(Long branchId, AssembledAiContext ctx, String instructions) {
        JsonNode first = attemptScoringCall(branchId, ctx, instructions);
        if (first != null) return first;
        JsonNode retried = attemptScoringCall(branchId, ctx, instructions + JSON_RETRY_REMINDER);
        if (retried != null) return retried;
        throw new AiProviderApiException("The AI provider returned an unparseable response for conversion scoring. Please try again.");
    }

    private JsonNode attemptScoringCall(Long branchId, AssembledAiContext ctx, String systemPrompt) {
        int estimatedPromptChars = systemPrompt.length();
        for (AiChatMessage m : ctx.getMessages()) estimatedPromptChars += m.getContent().length();

        AiUsageGuardService.Reservation reservation = usageGuard.checkAndReserve(
                branchId, AiUsageGuardService.Feature.CONVERSION_SCORING, estimatedPromptChars, MAX_OUTPUT_TOKENS);
        AiProviderResolver.Resolved resolved = providerResolver.resolveForBranch(branchId);

        AiChatResponse response = meteredCalls.chat("CONVERSION_SCORING", resolved.getClient(), resolved.getConfig(), AiChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(ctx.getMessages())
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.3)
                .expectJson(true)
                .build());

        usageGuard.recordActual(reservation, response.getPromptTokens(), response.getCompletionTokens());
        return tryParseJson(response.getContent());
    }

    private JsonNode tryParseJson(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception ex) {
            return null;
        }
    }

    private Lead requireLead(String leadNumber) {
        Lead lead = leadRepo.findByLeadNumber(leadNumber)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadNumber));
        return access.record(Lead.class, lead.getId());
    }

    private AiConversionScoreDto toDto(Lead lead, LeadConversionScore s) {
        return AiConversionScoreDto.builder()
                .leadId(lead.getId())
                .leadNumber(lead.getLeadNumber())
                .score(s.getScore())
                .strengthLabel(s.getStrengthLabel())
                .urgencyLabel(s.getUrgencyLabel())
                .rationale(s.getRationale())
                .signalsJson(s.getSignalsJson())
                .basedOnMessageCount(s.getBasedOnMessageCount())
                .generatedByUserName(s.getGeneratedByUser() != null ? s.getGeneratedByUser().getFullName() : null)
                .generatedAt(s.getGeneratedAt())
                .build();
    }
}
