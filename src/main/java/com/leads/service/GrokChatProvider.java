package com.leads.service;

import com.leads.model.enums.AiProviderType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/** xAI's Grok API is deliberately OpenAI-compatible ({@code POST /chat/completions} at api.x.ai/v1). */
@Service
public class GrokChatProvider extends AbstractOpenAiCompatibleChatProvider {

    public GrokChatProvider(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.GROK;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.x.ai/v1";
    }

    @Override
    protected boolean supportsJsonMode() {
        return false;
    }
}
