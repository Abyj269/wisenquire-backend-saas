package com.leads.service;

import com.leads.model.enums.AiProviderType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.regex.Pattern;

@Service
public class OpenAiChatProvider extends AbstractOpenAiCompatibleChatProvider {

    /** o-series and gpt-5.x are OpenAI's reasoning-line models; see {@link #supportsCustomTemperature}. */
    private static final Pattern REASONING_MODEL = Pattern.compile("^(o[1-9](-|$)|gpt-5)", Pattern.CASE_INSENSITIVE);

    public OpenAiChatProvider(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.OPENAI;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    protected String maxTokensParamName() {
        return "max_completion_tokens";
    }

    @Override
    protected boolean supportsCustomTemperature(String modelName) {
        return modelName == null || !REASONING_MODEL.matcher(modelName).find();
    }
}
