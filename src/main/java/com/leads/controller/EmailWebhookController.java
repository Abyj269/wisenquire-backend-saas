package com.leads.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.mail.EmailSuppressions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Module 02 P2 item 4: a provider-neutral bounce/complaint webhook (SMTP itself has none). The caller signs the raw
 * body with HMAC-SHA256 over {@code app.mail.webhook-secret} and sends {@code X-Signature: sha256=<hex>}. With no
 * secret configured the webhook is off (401). Body: {@code {"email": "...", "type": "hard_bounce"|"complaint"|"soft_bounce"}};
 * a soft bounce is ignored (the outbox retries).
 */
@RestController
@RequestMapping("/email/webhook")
@RequiredArgsConstructor
@Tag(name = "Email webhook", description = "Bounce and complaint notices from the mail provider (signed)")
public class EmailWebhookController {

    private final EmailSuppressions suppressions;
    private final ObjectMapper mapper;

    @Value("${app.mail.webhook-secret:}")
    private String secret = "";

    @PostMapping("/bounce")
    @Operation(summary = "Record a bounce or complaint", description = "Signed with HMAC-SHA256 (X-Signature: sha256=<hex>). "
            + "A hard bounce or complaint stops all further email to the address. 204 on success, 401 for a missing or wrong signature.")
    public ResponseEntity<Void> bounce(@RequestBody byte[] body, @RequestHeader(value = "X-Signature", required = false) String signature) throws Exception {
        if (secret == null || secret.isBlank() || signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}") || body.length > 65536)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7))))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        JsonNode event = mapper.readTree(body);
        String address = event.path("email").asText("");
        String type = event.path("type").asText("");
        if (address.isBlank() || !address.contains("@")) return ResponseEntity.badRequest().build();
        switch (type) {
            case "hard_bounce" -> suppressions.suppress(address, EmailSuppressions.Reason.HARD_BOUNCE, "webhook");
            case "complaint" -> suppressions.suppress(address, EmailSuppressions.Reason.COMPLAINT, "webhook");
            case "soft_bounce" -> { } // temporary: the outbox retries
            default -> { return ResponseEntity.badRequest().build(); }
        }
        return ResponseEntity.noContent().build();
    }
}
