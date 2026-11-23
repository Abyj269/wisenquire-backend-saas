package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.exception.AiProviderApiException;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** OpenAI's Whisper transcription endpoint ({@code POST /v1/audio/transcriptions}, multipart/form-data). */
@Service
public class OpenAiTranscriptionProvider implements AiTranscriptionProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private final WebClient.Builder webClientBuilder;

    public OpenAiTranscriptionProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.OPENAI;
    }

    @Override
    public String transcribe(AiProviderConfig config, byte[] audioBytes, String mimeType, String filename) {
        String baseUrl = config.getApiBaseUrlOverride() != null && !config.getApiBaseUrlOverride().isBlank()
                ? config.getApiBaseUrlOverride() : "https://api.openai.com/v1";
        String resolvedFilename = filename != null ? filename : "voice-note.ogg";

        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() { return resolvedFilename; }
        }).contentType(MediaType.parseMediaType(mimeType != null ? mimeType : "audio/ogg"));
        multipart.part("model", "whisper-1");

        try {
            JsonNode response = webClientBuilder.build().post()
                    .uri(baseUrl + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(msg -> new AiProviderApiException("OpenAI transcription API error (" + resp.statusCode().value() + "): " + msg)))
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            return response != null ? response.path("text").asText("") : "";
        } catch (AiProviderApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiProviderApiException("Failed to reach OpenAI for transcription: " + ex.getMessage(), ex);
        }
    }
}
