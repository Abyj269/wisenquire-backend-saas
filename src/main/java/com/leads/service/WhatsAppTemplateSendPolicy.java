package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.dto.whatsapp.SendMessageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Re-check Meta's live category/status before an operator's selected template is sent. */
final class WhatsAppTemplateSendPolicy {
    static void validate(JsonNode template, SendMessageRequest request) {
        if (!"APPROVED".equals(template.path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This template is no longer approved. Refresh the template list.");
        if (request.getExpectedTemplateCategory() != null && !request.getExpectedTemplateCategory().equals(template.path("category").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Meta changed this template's category to " + template.path("category").asText() + ". Refresh and review the category before sending.");
        if ("AUTHENTICATION".equals(template.path("category").asText()))
            throw new IllegalArgumentException("This template requires parameters not supported by the current chat sender. Use a standard text template.");
        Set<String> placeholders = new HashSet<>();
        for (var c : template.path("components")) {
            String type = c.path("type").asText();
            if ("HEADER".equals(type) && (!"TEXT".equals(c.path("format").asText()) || c.path("text").asText().contains("{{"))
                    || Set.of("CAROUSEL", "LIMITED_TIME_OFFER").contains(type))
                throw new IllegalArgumentException("This template needs media or advanced parameters that the current chat sender cannot collect.");
            if ("BUTTONS".equals(type)) for (var b : c.path("buttons"))
                if (Set.of("COPY_CODE", "FLOW", "OTP", "CATALOG", "MPM").contains(b.path("type").asText()) || b.path("url").asText().contains("{{"))
                    throw new IllegalArgumentException("This template needs button parameters that the current chat sender cannot collect.");
            if ("BODY".equals(type)) {
                var matcher = java.util.regex.Pattern.compile("\\{\\{[a-zA-Z0-9_]+}}").matcher(c.path("text").asText());
                while (matcher.find()) placeholders.add(matcher.group());
            }
        }
        var params = request.getTemplateBodyParams() == null ? List.<String>of() : request.getTemplateBodyParams();
        if (params.size() != placeholders.size() || params.stream().anyMatch(p -> p == null || p.isBlank()))
            throw new IllegalArgumentException("Provide one nonempty value for each template body variable.");
    }
}
