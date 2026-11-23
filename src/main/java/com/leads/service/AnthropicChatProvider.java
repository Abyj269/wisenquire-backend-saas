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
 * Anthropic's Messages API ({@code POST /v1/messages}) — genuinely different
 * from the OpenAI shape: auth is {@code x-api-key} + {@code anthropic-version}
 * (no Bearer), the system prompt is a dedicated top-level {@code system}
 * field (never a "system"-role message), roles are user/assistant only,
 * {@code max_tokens} is REQUIRED, and the response text is an array of
 * content blocks ({@code content[].text}) that must be concatenated rather
 * than a single string.
 */
@Service
public class AnthropicChatProvider implements AiChatProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final WebClient.Builder webClientBuilder;

    public AnthropicChatProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.ANTHROPIC;
    }

    @Override
    public AiChatResponse complete(AiProviderConfig config, AiChatRequest request) {
        String baseUrl = config.getApiBaseUrlOverride() != null && !config.getApiBaseUrlOverride().isBlank()
                ? config.getApiBaseUrlOverride() : "https://api.anthropic.com/v1";

        var messages = new java.util.ArrayList<Map<String, Object>>();
        for (AiChatMessage m : request.getMessages()) {
            messages.add(Map.of("role", m.getRole() == AiChatRole.ASSISTANT ? "assistant" : "user", "content", m.getContent()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        // Required by Anthropic, unlike the other 3 providers.
        body.put("max_tokens", request.getMaxOutputTokens() != null ? request.getMaxOutputTokens() : DEFAULT_MAX_TOKENS);
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("system", request.getSystemPrompt());
        }
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());

        JsonNode response = AiHttpSupport.postJson(webClientBuilder, baseUrl + "/messages",
                Map.of("x-api-key", config.getApiKey(), "anthropic-version", ANTHROPIC_VERSION),
                body, type().name());

        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
        }
        JsonNode usage = response.path("usage");
        return AiChatResponse.builder()
                .content(text.toString())
                .finishReason(response.path("stop_reason").asText(null))
                .promptTokens(usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null)
                .completionTokens(usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null)
                .build();
    }
}
