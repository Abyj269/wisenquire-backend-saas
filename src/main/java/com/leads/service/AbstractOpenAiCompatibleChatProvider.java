package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiChatRole;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared request/response mapping for the OpenAI Chat Completions shape
 * ({@code POST /chat/completions}, {@code Authorization: Bearer}, system as a
 * {@code role:"system"} message, {@code choices[0].message.content}). Grok's
 * API is genuinely OpenAI-compatible, so {@link OpenAiChatProvider} and
 * {@link GrokChatProvider} are thin subclasses of this rather than
 * duplicating the mapping — Anthropic and Gemini stay standalone classes
 * given their genuinely different shapes (see {@link AiChatProvider}).
 */
abstract class AbstractOpenAiCompatibleChatProvider implements AiChatProvider {

    protected final WebClient.Builder webClientBuilder;

    protected AbstractOpenAiCompatibleChatProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    protected abstract String defaultBaseUrl();

    /** OpenAI has a real JSON mode; not every OpenAI-compatible vendor does, so Grok opts out. */
    protected boolean supportsJsonMode() {
        return true;
    }

    /**
     * OpenAI's newer models (o-series, gpt-5.x) reject {@code max_tokens} with a 400
     * ("Unsupported parameter") and require {@code max_completion_tokens} instead, so
     * {@link OpenAiChatProvider} overrides this; Grok's API still expects {@code max_tokens}.
     */
    protected String maxTokensParamName() {
        return "max_tokens";
    }

    /**
     * OpenAI's reasoning-line models (o-series, gpt-5.x) lock sampling to the default
     * temperature of 1 and reject any other value with a 400 ("Unsupported value").
     * {@link OpenAiChatProvider} overrides this to detect those models by name; Grok's
     * models all accept a custom temperature.
     */
    protected boolean supportsCustomTemperature(String modelName) {
        return true;
    }

    @Override
    public AiChatResponse complete(AiProviderConfig config, AiChatRequest request) {
        String baseUrl = config.getApiBaseUrlOverride() != null && !config.getApiBaseUrlOverride().isBlank()
                ? config.getApiBaseUrlOverride() : defaultBaseUrl();

        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        for (AiChatMessage m : request.getMessages()) {
            messages.add(Map.of("role", m.getRole() == AiChatRole.ASSISTANT ? "assistant" : "user", "content", m.getContent()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        if (request.getMaxOutputTokens() != null) body.put(maxTokensParamName(), request.getMaxOutputTokens());
        if (request.getTemperature() != null && supportsCustomTemperature(config.getModelName())) {
            body.put("temperature", request.getTemperature());
        }
        if (request.isExpectJson() && supportsJsonMode()) body.put("response_format", Map.of("type", "json_object"));

        JsonNode response = AiHttpSupport.postJson(webClientBuilder, baseUrl + "/chat/completions",
                Map.of("Authorization", "Bearer " + config.getApiKey()), body, type().name());

        JsonNode choice = response.path("choices").path(0);
        JsonNode usage = response.path("usage");
        return AiChatResponse.builder()
                .content(choice.path("message").path("content").asText(""))
                .finishReason(choice.path("finish_reason").asText(null))
                .promptTokens(usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null)
                .completionTokens(usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null)
                .build();
    }
}
