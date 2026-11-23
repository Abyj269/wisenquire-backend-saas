package com.leads.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * AES-256-GCM at rest for AI provider API keys (OpenAI/Anthropic/Gemini/Grok)
 * — a leaked key here is direct billing exposure on the org's own provider
 * account, the same risk tier {@link AdCredentialEncryptor} already treats
 * ad-provider credentials as. Delegates to it rather than reimplementing
 * AES-GCM, only adding a distinct versioned prefix so a stored value is
 * unambiguously "an AI credential" if ever inspected/migrated independently
 * of ad credentials, mirroring {@link WhatsAppCredentialEncryptor}.
 */
@Converter
public class AiCredentialEncryptor implements AttributeConverter<String, String> {

    private static final String PREFIX = "ai:v1:";
    private final AdCredentialEncryptor delegate = new AdCredentialEncryptor();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return PREFIX + delegate.convertToDatabaseColumn(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String raw = dbData.startsWith(PREFIX) ? dbData.substring(PREFIX.length()) : dbData;
        return delegate.convertToEntityAttribute(raw);
    }
}
