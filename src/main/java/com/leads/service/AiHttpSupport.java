package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.exception.AiProviderApiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP mechanics (timeout, error-body extraction, exception wrapping)
 * for the 4 AI provider clients — NOT shared request/response domain
 * mapping, which genuinely differs per provider and stays in each client
 * class (see {@link AiChatProvider}'s javadoc for why).
 */
final class AiHttpSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ERROR_BODY_MAX_CHARS = 500;

    private AiHttpSupport() {}

    static JsonNode postJson(WebClient.Builder webClientBuilder, String url, Map<String, String> headers,
                              Object body, String providerName) {
        try {
            WebClient.RequestBodySpec spec = webClientBuilder.build().post().uri(url).contentType(MediaType.APPLICATION_JSON);
            headers.forEach(spec::header);
            return spec.bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(msg -> new AiProviderApiException(providerName + " API error ("
                                    + response.statusCode().value() + "): " + truncate(msg))))
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
        } catch (AiProviderApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiProviderApiException("Failed to reach " + providerName + ": " + ex.getMessage(), ex);
        }
    }

    private static String truncate(String s) {
        return s.length() > ERROR_BODY_MAX_CHARS ? s.substring(0, ERROR_BODY_MAX_CHARS) + "..." : s;
    }
}
