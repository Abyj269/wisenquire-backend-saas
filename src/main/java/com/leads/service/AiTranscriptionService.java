package com.leads.service;

import com.leads.dto.ai.AiTranscriptionDto;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.entity.Branch;
import com.leads.model.entity.WhatsAppMessage;
import com.leads.model.entity.WhatsAppMessageTranscription;
import com.leads.model.enums.AiProviderType;
import com.leads.repository.WhatsAppMessageTranscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-demand voice-note (audio message) transcription, cached forever after
 * first request — same immutability/caching shape as
 * {@link AiTranslationService}. Scoped to OpenAI-only for now (the only
 * provider of the 4 with a real transcription API today); a branch whose
 * selected provider is Anthropic/Gemini/Grok gets a clear "not available"
 * error rather than a confusing failure.
 */
@Service
@RequiredArgsConstructor
public class AiTranscriptionService {
    private final AiMeteredCalls meteredCalls;

    private static final int ESTIMATED_TOKENS_PER_MINUTE = 150;

    private final TenantAccess access;
    private final WhatsAppMessageTranscriptionRepository transcriptionRepo;
    private final WhatsAppMediaService mediaService;
    private final AiUsageGuardService usageGuard;
    private final AiProviderResolver providerResolver;
    private final List<AiTranscriptionProvider> transcriptionProviders;
    private Map<AiProviderType, AiTranscriptionProvider> byType;

    @Transactional(readOnly = true)
    public Optional<AiTranscriptionDto> getCached(Long messageId) {
        access.record(WhatsAppMessage.class, messageId);
        return transcriptionRepo.findByMessageId(messageId).map(t -> toDto(messageId, t));
    }

    @Transactional
    public AiTranscriptionDto transcribe(Long messageId) {
        WhatsAppMessage message = access.record(WhatsAppMessage.class, messageId);
        Optional<WhatsAppMessageTranscription> existing = transcriptionRepo.findByMessageId(messageId);
        if (existing.isPresent()) return toDto(messageId, existing.get());

        if (!"AUDIO".equals(message.getMessageType())) {
            throw new IllegalArgumentException("Only voice-note (audio) messages can be transcribed.");
        }
        Branch branch = message.getConversation().getBranch();
        if (branch == null) {
            throw new IllegalStateException("This conversation has no branch assigned; AI features require a branch.");
        }

        AiProviderResolver.Resolved resolved = providerResolver.resolveForBranch(branch.getId());
        AiProviderConfig config = resolved.getConfig();
        if (config.getProvider() != AiProviderType.OPENAI) {
            throw new IllegalStateException("Voice-note transcription is only available when this branch's selected AI "
                    + "provider is OpenAI. Configure an OpenAI connection in AI Settings to use this feature.");
        }
        AiTranscriptionProvider transcriptionClient = clientFor(config.getProvider());

        // Audio isn't measured in characters — a flat conservative per-call
        // estimate is used for admission-control purposes only; real usage
        // reconciliation isn't available for this endpoint (OpenAI's
        // transcription API doesn't report token usage), so the estimate
        // simply stands (see AiUsageGuardService.recordActual's null-usage path).
        AiUsageGuardService.Reservation reservation = usageGuard.checkAndReserve(
                branch.getId(), AiUsageGuardService.Feature.TRANSCRIPTION, 0, ESTIMATED_TOKENS_PER_MINUTE * 3);

        WhatsAppMediaService.CachedMedia media = mediaService.resolve(messageId);
        String text = meteredCalls.transcribe(transcriptionClient, config, media.data(), media.mimeType(), media.filename());
        usageGuard.recordActual(reservation, null, null);

        WhatsAppMessageTranscription transcription = new WhatsAppMessageTranscription();
        transcription.setMessage(message);
        transcription.setCompany(message.getConversation().getCompany());
        transcription.setTranscriptText(text != null ? text.trim() : "");
        transcription.setProvider(config.getProvider().name());
        transcription.setModelName("whisper-1");
        transcription.setGeneratedByUser(access.actor());

        return toDto(messageId, transcriptionRepo.save(transcription));
    }

    private AiTranscriptionProvider clientFor(AiProviderType type) {
        if (byType == null) {
            Map<AiProviderType, AiTranscriptionProvider> map = new EnumMap<>(AiProviderType.class);
            for (AiTranscriptionProvider p : transcriptionProviders) map.put(p.type(), p);
            byType = map;
        }
        AiTranscriptionProvider client = byType.get(type);
        if (client == null) throw new IllegalStateException("Transcription is not supported for provider " + type);
        return client;
    }

    private AiTranscriptionDto toDto(Long messageId, WhatsAppMessageTranscription t) {
        return AiTranscriptionDto.builder().messageId(messageId).transcriptText(t.getTranscriptText()).build();
    }
}
