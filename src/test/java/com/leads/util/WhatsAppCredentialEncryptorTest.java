package com.leads.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhatsAppCredentialEncryptorTest {
    @Test void encryptsWithRandomNonceAndReadsLegacyForMigration() {
        var converter = new WhatsAppCredentialEncryptor();
        String first = converter.convertToDatabaseColumn("sample-provider-secret");
        String second = converter.convertToDatabaseColumn("sample-provider-secret");
        assertNotEquals(first, second);
        assertFalse(first.contains("sample-provider-secret"));
        assertEquals("sample-provider-secret", converter.convertToEntityAttribute(first));
        assertEquals("legacy-token", converter.convertToEntityAttribute("legacy-token"));
        assertNull(converter.convertToEntityAttribute(null));
    }
    @Test void authenticationTagRejectsModifiedCiphertext() {
        var converter = new WhatsAppCredentialEncryptor();
        String encrypted = converter.convertToDatabaseColumn("sample-provider-secret");
        byte[] bytes = java.util.Base64.getDecoder().decode(encrypted.substring(WhatsAppCredentialEncryptor.PREFIX.length()));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(
                WhatsAppCredentialEncryptor.PREFIX + java.util.Base64.getEncoder().encodeToString(bytes)));
    }
}
