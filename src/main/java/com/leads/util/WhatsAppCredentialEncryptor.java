package com.leads.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Version marker allows a controlled migration of existing plaintext rows. */
@Converter
public class WhatsAppCredentialEncryptor implements AttributeConverter<String, String> {
    public static final String PREFIX = "wa:v1:";
    private final AdCredentialEncryptor encryption = new AdCredentialEncryptor();

    @Override public String convertToDatabaseColumn(String value) {
        return value == null ? null : PREFIX + encryption.convertToDatabaseColumn(value);
    }

    @Override public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        return stored.startsWith(PREFIX) ? encryption.convertToEntityAttribute(stored.substring(PREFIX.length())) : stored;
    }
}
