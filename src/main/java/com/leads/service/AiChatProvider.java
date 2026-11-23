package com.leads.service;

import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;

/**
 * One implementation per {@link AiProviderType} (OpenAI, Anthropic, Gemini,
 * Grok) — {@link AiProviderResolver} picks the right one for a branch so no
 * calling service branches on provider itself. Mirrors the existing
 * {@link AdsProviderClient}/{@link AdsProviderClientFactory} pattern.
 * <p>
 * The four providers' chat-completion REST APIs differ in real, unavoidable
 * ways that each implementation must absorb on its own:
 * <ul>
 *   <li>System prompt: OpenAI/Grok take it as a {@code role:"system"} message;
 *       Anthropic requires a dedicated top-level {@code system} field (a
 *       "system"-role message inside {@code messages} is rejected); Gemini
 *       requires a dedicated {@code systemInstruction.parts[].text} field.</li>
 *   <li>Roles: OpenAI/Grok use system/user/assistant; Anthropic user/assistant
 *       only; Gemini user/"model" (assistant is literally named "model").</li>
 *   <li>Auth: OpenAI/Grok use {@code Authorization: Bearer}; Anthropic uses
 *       {@code x-api-key} + an {@code anthropic-version} header (no Bearer);
 *       Gemini uses an {@code x-goog-api-key} header (never a query param,
 *       which would leak the key into access logs/proxies).</li>
 *   <li>{@code max_tokens} is optional for OpenAI/Grok/Gemini but REQUIRED by
 *       Anthropic, which errors without it.</li>
 *   <li>Response text: OpenAI/Grok {@code choices[0].message.content} (a
 *       string); Anthropic {@code content[]} (an array of content blocks that
 *       must be concatenated); Gemini
 *       {@code candidates[0].content.parts[0].text}.</li>
 *   <li>Usage: OpenAI/Grok {@code usage.prompt_tokens}/{@code completion_tokens};
 *       Anthropic {@code usage.input_tokens}/{@code output_tokens}; Gemini
 *       {@code usageMetadata.promptTokenCount}/{@code candidatesTokenCount}.</li>
 * </ul>
 * Since Grok is genuinely OpenAI-compatible, {@link OpenAiChatProvider} and
 * {@link GrokChatProvider} share their request/response mapping via
 * {@link AbstractOpenAiCompatibleChatProvider}; Anthropic and Gemini stay
 * fully standalone given their genuinely different shapes.
 */
public interface AiChatProvider {

    AiProviderType type();

    /**
     * Blocking call (each implementation uses WebClient in blocking mode,
     * {@code .block(Duration)}) — every caller above this stays ordinary
     * synchronous Spring MVC/JPA code, consistent with the rest of the app.
     * Runs on the request thread, same as every other outbound HTTP call in
     * this codebase (e.g. {@link WhatsAppGraphApiClient}).
     *
     * @throws com.leads.exception.AiProviderApiException on any upstream failure
     */
    AiChatResponse complete(AiProviderConfig config, AiChatRequest request);
}
