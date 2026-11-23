package com.leads.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM at rest for ad-provider credentials (Meta access token, Google
 * client secret/refresh token/developer token) — these are more sensitive
 * than most stored secrets in this codebase (a leaked Google refresh token
 * grants standing account access, not just a revocable session), so unlike
 * WhatsAppConfiguration's plaintext columns this is genuinely encrypted, not
 * just masked-on-read.
 * <p>
 * Not a Spring bean: JPA AttributeConverters are instantiated by the
 * persistence provider, not the Spring container, so the key is read
 * directly from the environment rather than via {@code @Value}. Stored
 * format is {@code Base64(iv || ciphertext+tag)} — GCM appends the auth tag
 * to the ciphertext automatically, so only the random 12-byte IV needs to be
 * prepended by hand.
 */
@Slf4j
@Converter
public class AdCredentialEncryptor implements AttributeConverter<String, String> {

    private static final String ENV_KEY_NAME = "ADS_CREDENTIALS_ENCRYPTION_KEY";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    // Dev-only fallback so local/windows-dev environments work without extra
    // setup — this exact key must never be relied on in application-prod.yml
    // / a real deployment; ops is expected to set ADS_CREDENTIALS_ENCRYPTION_KEY.
    private static final String DEV_FALLBACK_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static volatile SecretKeySpec cachedKey;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt ad credential", ex);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decrypt ad credential", ex);
        }
    }

    private static SecretKeySpec resolveKey() {
        SecretKeySpec key = cachedKey;
        if (key != null) return key;
        synchronized (AdCredentialEncryptor.class) {
            if (cachedKey == null) {
                String base64Key = System.getenv(ENV_KEY_NAME);
                if (base64Key == null || base64Key.isBlank()) {
                    log.warn("{} is not set — falling back to the built-in dev-only encryption key. " +
                            "This is NOT safe for production: set {} to a real base64-encoded 256-bit key.",
                            ENV_KEY_NAME, ENV_KEY_NAME);
                    base64Key = DEV_FALLBACK_KEY_BASE64;
                }
                cachedKey = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
            }
            return cachedKey;
        }
    }
}
