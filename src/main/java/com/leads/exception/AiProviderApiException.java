package com.leads.exception;

/**
 * Thrown when a call to a configured AI provider's API (OpenAI/Anthropic/
 * Gemini/Grok) fails — invalid/revoked key, malformed request, network/
 * timeout, provider-side outage or rate limiting, etc. Deliberately distinct
 * from {@link AiUsageLimitExceededException}: this means "the provider
 * itself is unavailable", not "your branch is over its configured usage cap".
 */
public class AiProviderApiException extends RuntimeException {
    public AiProviderApiException(String message) {
        super(message);
    }

    public AiProviderApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
