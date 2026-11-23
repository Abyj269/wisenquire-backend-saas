package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiChatRole;
import com.leads.model.enums.AiProviderType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google's Gemini generateContent API — genuinely different shape again: the
 * model name is part of the URL path, auth is an {@code x-goog-api-key}
 * header (never a query param, which would leak the key into access logs/
 * proxies), the system prompt is a dedicated {@code systemInstruction.parts}
 * field, roles are user/"model" (assistant is literally named "model"), and
 * the response text is {@code candidates[0].content.parts[0].text}.
 */
@Service
public class GeminiChatProvider implements AiChatProvider {

    private final WebClient.Builder webClientBuilder;

    public GeminiChatProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.GEMINI;
    }

    @Override
    public AiChatResponse complete(AiProviderConfig config, AiChatRequest request) {
        String baseUrl = config.getApiBaseUrlOverride() != null && !config.getApiBaseUrlOverride().isBlank()
                ? config.getApiBaseUrlOverride() : "https://generativelanguage.googleapis.com/v1beta";

        var contents = new java.util.ArrayList<Map<String, Object>>();
        for (AiChatMessage m : request.getMessages()) {
            contents.add(Map.of(
                    "role", m.getRole() == AiChatRole.ASSISTANT ? "model" : "user",
                    "parts", java.util.List.of(Map.of("text", m.getContent()))
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", contents);
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("systemInstruction", Map.of("parts", java.util.List.of(Map.of("text", request.getSystemPrompt()))));
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.getMaxOutputTokens() != null) generationConfig.put("maxOutputTokens", request.getMaxOutputTokens());
        if (request.getTemperature() != null) generationConfig.put("temperature", request.getTemperature());
        if (request.isExpectJson()) generationConfig.put("responseMimeType", "application/json");
        if (!generationConfig.isEmpty()) body.put("generationConfig", generationConfig);

        String url = baseUrl + "/models/" + config.getModelName() + ":generateContent";
        JsonNode response = AiHttpSupport.postJson(webClientBuilder, url,
                Map.of("x-goog-api-key", config.getApiKey()), body, type().name());

        JsonNode candidate = response.path("candidates").path(0);
        JsonNode usage = response.path("usageMetadata");
        return AiChatResponse.builder()
                .content(candidate.path("content").path("parts").path(0).path("text").asText(""))
                .finishReason(candidate.path("finishReason").asText(null))
                .promptTokens(usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null)
                .completionTokens(usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null)
                .build();
    }
}
