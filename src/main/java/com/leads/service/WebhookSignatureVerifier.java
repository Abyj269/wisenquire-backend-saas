package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.repository.WhatsAppConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class WebhookSignatureVerifier {
    private final WhatsAppConfigurationRepository configs;
    private final ObjectMapper mapper;
    /**
     * Verifies the HMAC against the receiving numbers' own app secrets and returns the parsed payload
     * (module 12 P1: parsed once, reused by the controller). A change for an unknown number is skipped;
     * at least one change must belong to a known number whose secret matches the whole body.
     */
    public com.fasterxml.jackson.databind.JsonNode verify(byte[] body, String signature) {
        if(body.length>1048576 || signature==null || !signature.matches("sha256=[0-9a-fA-F]{64}")) throw denied();
        try {
            boolean found=false;
            var tree=mapper.readTree(body);
            for(var entry:tree.path("entry")) for(var change:entry.path("changes")) {
                String number=change.path("value").path("metadata").path("phone_number_id").asText();
                var candidates = number.isBlank() && WhatsAppTemplateEventService.FIELDS.contains(change.path("field").asText())
                    ? configs.findByWabaIdAndIsActiveTrue(entry.path("id").asText())
                    : configs.findByPhoneNumberId(number).map(java.util.List::of).orElse(java.util.List.of());
                if (candidates.isEmpty()) continue; // unknown number: skipped here and in processWebhookPayload
                boolean matched = false;
                for (var config : candidates) {
                if(!Boolean.TRUE.equals(config.getIsActive()) || config.getAppSecret()==null || config.getAppSecret().isBlank()) continue;
                Mac mac=Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(config.getAppSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                if(MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7)))) matched = true;
                }
                if (!matched) throw denied();
                found=true;
            }
            if(!found) throw denied();
            return tree;
        } catch(AccessDeniedException e){throw e;} catch(Exception e){throw denied();}
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Invalid webhook signature or receiving number.");}
}
