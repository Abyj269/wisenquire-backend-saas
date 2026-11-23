package com.leads.service;
import com.leads.dto.ai.*;
import com.leads.model.entity.AiProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Only wraps actual outbound calls; cache hits and locally rejected actions are not charged. */
@Service @RequiredArgsConstructor
public class AiMeteredCalls {
    private final AiUsageLedger ledger;
    public AiChatResponse chat(String feature, AiChatProvider client, AiProviderConfig config, AiChatRequest request) {
        var ticket = ledger.start(config, config.getModelName(), feature);
        AiChatResponse response;
        try { response = client.complete(config, request); }
        catch (RuntimeException error) { ledger.finish(ticket, "FAILED", null, null); throw error; }
        ledger.finish(ticket, "SUCCEEDED", response.getPromptTokens(), response.getCompletionTokens());
        return response;
    }
    public String transcribe(AiTranscriptionProvider client, AiProviderConfig config, byte[] data, String mime, String name) {
        var ticket = ledger.start(config, "whisper-1", "TRANSCRIPTION");
        String text;
        try { text = client.transcribe(config, data, mime, name); }
        catch (RuntimeException error) { ledger.finish(ticket, "FAILED", null, null); throw error; }
        // Current adapter returns text only: do not invent audio duration or token usage.
        ledger.finish(ticket, "SUCCEEDED", null, null);
        return text;
    }
}
