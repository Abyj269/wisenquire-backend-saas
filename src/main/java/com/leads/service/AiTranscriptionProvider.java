package com.leads.service;

import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;

/**
 * Voice-note (audio) transcription — a genuinely different API shape from
 * {@link AiChatProvider} (multipart audio upload, no messages/roles), so it
 * gets its own small interface rather than being bolted onto AiChatProvider.
 * Only OpenAI implements this today; provider support for transcription is
 * uneven across vendors (see {@link AiTranscriptionService}).
 */
public interface AiTranscriptionProvider {
    AiProviderType type();
    String transcribe(AiProviderConfig config, byte[] audioBytes, String mimeType, String filename);
}
