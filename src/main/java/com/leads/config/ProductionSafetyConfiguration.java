package com.leads.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import java.net.URI;

/** Fail before startup can seed a predictable administrator account. */
@Configuration
@Profile("prod")
public class ProductionSafetyConfiguration {
    public ProductionSafetyConfiguration(Environment env) {
        String jwt = required(env, "JWT_SECRET");
        if (jwt.length() < 64 || jwt.equals("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970")) {
            throw new IllegalStateException("Production requires an independent JWT secret of at least 64 characters.");
        }
        if (required(env, "SUPER_ADMIN_PASSWORD").length() < 16) {
            throw new IllegalStateException("Production bootstrap administrator password must have at least 16 characters.");
        }
        required(env, "SUPER_ADMIN_EMAIL");
        String encryptionKey = System.getenv("ADS_CREDENTIALS_ENCRYPTION_KEY");
        if (encryptionKey == null || encryptionKey.isBlank()) throw new IllegalStateException("Set ADS_CREDENTIALS_ENCRYPTION_KEY in the process environment; the JPA credential converter reads that environment directly.");
        if (encryptionKey.equals("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                || java.util.Base64.getDecoder().decode(encryptionKey).length != 32) {
            throw new IllegalStateException("Production requires an independent base64-encoded 256-bit credential encryption key.");
        }
        required(env, "SMTP_USERNAME");
        String smtp = required(env, "SMTP_PASSWORD");
        if (smtp.contains("REPLACE_WITH")) throw new IllegalStateException("Configure production SMTP credentials.");
        for (String origin : required(env, "ALLOWED_ORIGINS").split(",")) {
            URI uri = URI.create(origin.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalStateException("Production origins must be exact HTTPS origins without paths or wildcards.");
            }
        }
    }

    private static String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing production configuration: " + name);
        return value;
    }
}
