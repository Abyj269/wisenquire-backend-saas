package com.leads.service;

import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.dto.ai.AiTranslationDto;
import com.leads.model.entity.Branch;
import com.leads.model.entity.WhatsAppConversation;
import com.leads.model.entity.WhatsAppMessage;
import com.leads.model.entity.WhatsAppMessageTranslation;
import com.leads.model.enums.AiChatRole;
import com.leads.repository.WhatsAppMessageTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * On-demand English translation of one inbound message, cached forever after
 * first request — the paid AI call only ever happens once per message,
 * triggered by an agent clicking the translate icon (never automatically on
 * message receipt; see {@link LanguageDetectionService} for the free
 * heuristic that decides icon visibility).
 */
@Service
@RequiredArgsConstructor
public class AiTranslationService {
    private final AiMeteredCalls meteredCalls;

    private static final int MAX_OUTPUT_TOKENS = 500;

    private final TenantAccess access;
    private final WhatsAppMessageTranslationRepository translationRepo;
    private final AiUsageGuardService usageGuard;
    private final AiProviderResolver providerResolver;

    @Transactional(readOnly = true)
    public Optional<AiTranslationDto> getCached(Long messageId) {
        access.record(WhatsAppMessage.class, messageId);
        return translationRepo.findByMessageId(messageId).map(t -> toDto(messageId, t));
    }

    /** Idempotent — a second call for the same message returns the cached row without a second AI request. */
    @Transactional
    public AiTranslationDto translate(Long messageId) {
        WhatsAppMessage message = access.record(WhatsAppMessage.class, messageId);
        Optional<WhatsAppMessageTranslation> existing = translationRepo.findByMessageId(messageId);
        if (existing.isPresent()) return toDto(messageId, existing.get());

        if (message.getBodyText() == null || message.getBodyText().isBlank()) {
            throw new IllegalArgumentException("This message has no text to translate.");
        }
        WhatsAppConversation conversation = message.getConversation();
        Branch branch = conversation.getBranch();
        if (branch == null) {
            throw new IllegalStateException("This conversation has no branch assigned; AI features require a branch.");
        }

        String systemPrompt = "Translate the customer message below into English. "
                + "Reply with ONLY the translation — no explanation, no quotation marks.";
        int estimatedChars = systemPrompt.length() + message.getBodyText().length();

        AiUsageGuardService.Reservation reservation = usageGuard.checkAndReserve(
                branch.getId(), AiUsageGuardService.Feature.TRANSLATION, estimatedChars, MAX_OUTPUT_TOKENS);
        AiProviderResolver.Resolved resolved = providerResolver.resolveForBranch(branch.getId());

        AiChatResponse response = meteredCalls.chat("TRANSLATION", resolved.getClient(), resolved.getConfig(), AiChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(List.of(AiChatMessage.builder().role(AiChatRole.USER).content(message.getBodyText()).build()))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.2)
                .build());

        usageGuard.recordActual(reservation, response.getPromptTokens(), response.getCompletionTokens());

        WhatsAppMessageTranslation translation = new WhatsAppMessageTranslation();
        translation.setMessage(message);
        translation.setCompany(conversation.getCompany());
        translation.setSourceLanguage(message.getDetectedLanguage() != null ? message.getDetectedLanguage() : "auto");
        translation.setTranslatedText(response.getContent() != null ? response.getContent().trim() : "");
        translation.setProvider(resolved.getConfig().getProvider().name());
        translation.setModelName(resolved.getConfig().getModelName());
        translation.setGeneratedByUser(access.actor());

        return toDto(messageId, translationRepo.save(translation));
    }

    private AiTranslationDto toDto(Long messageId, WhatsAppMessageTranslation t) {
        return AiTranslationDto.builder()
                .messageId(messageId)
                .translatedText(t.getTranslatedText())
                .sourceLanguage(t.getSourceLanguage())
                .generatedAt(t.getCreatedAt())
                .build();
    }
}
