package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.dto.whatsapp.WhatsAppTemplateCreateRequest;
import com.leads.dto.whatsapp.WhatsAppTemplateDto;
import com.leads.dto.whatsapp.WhatsAppTemplateUsageDto;
import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.WhatsAppConfiguration;
import com.leads.model.enums.MessageStatus;
import com.leads.repository.WhatsAppConfigurationRepository;
import com.leads.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Proxies Meta's WhatsApp message template endpoints (list/create/delete) for
 * a specific connected number (WABA). A branch can have more than one number
 * connected, and each may belong to a different WABA, so templates are keyed
 * by the exact {@link WhatsAppConfiguration} (configId), not by branch.
 * Templates live on Meta's side — this service doesn't cache them locally, so
 * what you see here is always exactly what Meta has (including live approval
 * status), with no sync to go stale.
 *
 * Template *usage* (how many times a template was actually sent) is the one
 * thing Meta doesn't report back to us, so that part is counted locally from
 * {@link com.leads.model.entity.WhatsAppMessage} rows instead — see
 * {@link #getUsage}.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateService {
    // Module 13 P1 (CLAUDE.md hard rule 2): the Graph-calling methods have no transaction; the
    // config check and lookup run first in their own short repository transactions.
    private final TenantAccess access;
    private final CompanyClock companyClock;

    private final WhatsAppConfigurationRepository configRepo;
    private final WhatsAppMessageRepository messageRepo;
    private final WhatsAppGraphApiClient graphApiClient;
    private final WhatsAppTemplateEventService templateEvents;

    public List<WhatsAppTemplateDto> listTemplates(Long configId) {
        WhatsAppConfiguration config = resolveConfig(configId);
        var events = templateEvents.latestCategoryEvents(config.getWabaId());
        return graphApiClient.listTemplates(config).stream()
                .map(t -> { var dto = mapToDto(t); dto.setLatestCategoryEvent(events.get(dto.getId())); return dto; })
                .collect(Collectors.toList());
    }

    public WhatsAppTemplateDto createTemplate(WhatsAppTemplateCreateRequest req) {
        WhatsAppConfiguration config = resolveConfig(req.getConfigId());
        Map<String, Object> payload = buildTemplatePayload(req);
        JsonNode created = graphApiClient.createTemplate(config, payload);
        return WhatsAppTemplateDto.builder()
                .id(created.has("id") ? created.get("id").asText() : null)
                .name(req.getName())
                .status(created.has("status") ? created.get("status").asText() : "PENDING")
                .category(created.hasNonNull("category") ? created.get("category").asText() : req.getCategory())
                .language(req.getLanguage())
                .build();
    }

    /**
     * Edits an existing template's content in place. Meta's edit endpoint
     * doesn't accept name/language changes (those are immutable — create a
     * new template instead), only category + components, so this reuses the
     * same component-building logic as {@link #createTemplate} but wraps it
     * in the narrower payload shape the edit endpoint expects.
     */
    public WhatsAppTemplateDto updateTemplate(String templateId, WhatsAppTemplateCreateRequest req) {
        WhatsAppConfiguration config = resolveConfig(req.getConfigId());
        JsonNode existing = graphApiClient.requireTemplate(config, templateId);
        requireEditableComponents(existing.path("components"));
        if (!req.getName().equals(existing.path("name").asText()) || !req.getLanguage().equals(existing.path("language").asText()))
            throw new IllegalArgumentException("Template name and language cannot be changed.");
        if (!req.getParameterFormat().equals(existing.path("parameter_format").asText("POSITIONAL")))
            throw new IllegalArgumentException("Parameter format cannot be changed on an existing template.");
        WhatsAppTemplateValidation.validate(req);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", req.getCategory());
        payload.put("components", buildComponents(req));
        graphApiClient.updateTemplate(config, templateId, payload);
        return mapToDto(graphApiClient.requireTemplate(config, templateId));
    }

    private void requireEditableComponents(JsonNode components) {
        for (var c : components) {
            String type = c.path("type").asText();
            if (!java.util.Set.of("HEADER", "BODY", "FOOTER", "BUTTONS", "LIMITED_TIME_OFFER", "CAROUSEL").contains(type))
                throw new IllegalArgumentException("This template contains an advanced component. Edit it in WhatsApp Manager to preserve its settings.");
            if ("BUTTONS".equals(type)) for (var button : c.path("buttons")) {
                if (!java.util.Set.of("QUICK_REPLY", "URL", "PHONE_NUMBER", "COPY_CODE", "FLOW", "OTP").contains(button.path("type").asText())
                        || "OTP".equals(button.path("type").asText()) && !"COPY_CODE".equals(button.path("otp_type").asText("COPY_CODE")))
                    throw new IllegalArgumentException("Edit this advanced button in WhatsApp Manager to preserve its configuration.");
            }
            if ("HEADER".equals(type) && !java.util.Set.of("TEXT", "IMAGE", "VIDEO", "DOCUMENT", "LOCATION").contains(c.path("format").asText()))
                throw new IllegalArgumentException("Edit this header format in WhatsApp Manager.");
            if ("CAROUSEL".equals(type)) for (var card : c.path("cards")) requireEditableComponents(card.path("components"));
        }
    }

    public void deleteTemplate(Long configId, String templateName) {
        if (templateName == null || !templateName.matches("[a-z0-9_]{1,512}")) throw new IllegalArgumentException("Invalid template name.");
        WhatsAppConfiguration config = resolveConfig(configId);
        graphApiClient.deleteTemplate(config, templateName);
    }

    /** Uploads sample media for a template Image/Video/Document header (or a Carousel card's header) and returns its header_handle. */
    public String uploadHeaderMedia(Long configId, byte[] bytes, String mimeType, String filename) {
        WhatsAppConfiguration config = resolveConfig(configId);
        long limit = switch (java.util.Objects.toString(mimeType, "")) {
            case "image/jpeg", "image/png" -> 5L * 1024 * 1024;
            case "video/mp4", "video/3gpp" -> 16L * 1024 * 1024;
            case "application/pdf" -> 100L * 1024 * 1024;
            default -> throw new IllegalArgumentException("Sample media must be JPEG, PNG, MP4, 3GPP or PDF.");
        };
        if (bytes == null || bytes.length == 0 || bytes.length > limit) throw new IllegalArgumentException("Sample media is empty or exceeds the allowed file size.");
        String uploadSessionId = graphApiClient.createUploadSession(config, bytes.length, mimeType, filename);
        return graphApiClient.uploadSessionFile(config, uploadSessionId, bytes);
    }

    /**
     * Local per-template send counts for the given date range (both bounds optional — null means unbounded).
     * D15: the dates are local days of the number's company (CompanyClock), not server days.
     */
    @Transactional(readOnly = true)
    public List<WhatsAppTemplateUsageDto> getUsage(Long configId, LocalDate fromDay, LocalDate toDay) {
        WhatsAppConfiguration config = resolveConfig(configId);
        LocalDateTime from = fromDay == null ? null : companyClock.range(config.getCompany(), fromDay, fromDay).start();
        LocalDateTime to = toDay == null ? null : companyClock.range(config.getCompany(), toDay, toDay).endExclusive();
        // countTemplateUsage has no tenant parameter: it is only called here, after resolveConfig() checked the config.
        List<Object[]> rows = messageRepo.countTemplateUsage(config.getPhoneNumberId(), from, to);

        Map<String, WhatsAppTemplateUsageDto> byTemplate = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String templateName = (String) row[0];
            MessageStatus status = (MessageStatus) row[1];
            long count = (Long) row[2];

            WhatsAppTemplateUsageDto dto = byTemplate.computeIfAbsent(templateName, name ->
                    WhatsAppTemplateUsageDto.builder().templateName(name).build());
            dto.setTotal(dto.getTotal() + count);
            switch (status) {
                case SENT -> dto.setSent(dto.getSent() + count);
                case DELIVERED -> dto.setDelivered(dto.getDelivered() + count);
                case READ -> dto.setRead(dto.getRead() + count);
                case FAILED -> dto.setFailed(dto.getFailed() + count);
                case ACCEPTED -> dto.setAccepted(dto.getAccepted() + count);
                case UNKNOWN -> dto.setUnknown(dto.getUnknown() + count);
            }
        }
        return new ArrayList<>(byTemplate.values());
    }

    private WhatsAppConfiguration resolveConfig(Long configId) {
        access.record(com.leads.model.entity.WhatsAppConfiguration.class, configId);
        return configRepo.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("This WhatsApp number could not be found — it may have been disconnected."));
    }

    private Map<String, Object> buildTemplatePayload(WhatsAppTemplateCreateRequest req) {
        WhatsAppTemplateValidation.validate(req);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", req.getName());
        payload.put("category", req.getCategory());
        payload.put("language", req.getLanguage());
        payload.put("parameter_format", req.getParameterFormat());
        payload.put("components", buildComponents(req));
        return payload;
    }

    private List<Map<String, Object>> buildComponents(WhatsAppTemplateCreateRequest req) {
        boolean isAuthentication = "AUTHENTICATION".equals(req.getCategory());
        boolean isCarousel = req.getCarouselCards() != null && !req.getCarouselCards().isEmpty();

        List<Map<String, Object>> components = new ArrayList<>();

        if (isAuthentication) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "BODY");
            if (Boolean.TRUE.equals(req.getAddSecurityRecommendation())) {
                body.put("add_security_recommendation", true);
            }
            components.add(body);

            if (req.getCodeExpirationMinutes() != null) {
                Map<String, Object> footer = new LinkedHashMap<>();
                footer.put("type", "FOOTER");
                footer.put("code_expiration_minutes", req.getCodeExpirationMinutes());
                components.add(footer);
            }

            if (req.getButtons() != null && !req.getButtons().isEmpty()) {
                List<Map<String, Object>> buttons = req.getButtons().stream()
                        .map(this::buildButton)
                        .collect(Collectors.toList());
                Map<String, Object> buttonsComponent = new LinkedHashMap<>();
                buttonsComponent.put("type", "BUTTONS");
                buttonsComponent.put("buttons", buttons);
                components.add(buttonsComponent);
            }
        } else {
            addHeaderComponent(components, req.getHeaderType(), req.getHeaderText(), req.getHeaderExampleValue(), req.getHeaderMediaHandle());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "BODY");
            body.put("text", req.getBodyText());
            if (req.getBodyExampleValues() != null && !req.getBodyExampleValues().isEmpty()) {
                body.put("example", Map.of("body_text", List.of(req.getBodyExampleValues())));
            }
            components.add(body);

            if (req.getFooterText() != null && !req.getFooterText().isBlank()) {
                Map<String, Object> footer = new LinkedHashMap<>();
                footer.put("type", "FOOTER");
                footer.put("text", req.getFooterText());
                components.add(footer);
            }

            if (req.getButtons() != null && !req.getButtons().isEmpty()) {
                List<Map<String, Object>> buttons = req.getButtons().stream()
                        .map(this::buildButton)
                        .collect(Collectors.toList());
                Map<String, Object> buttonsComponent = new LinkedHashMap<>();
                buttonsComponent.put("type", "BUTTONS");
                buttonsComponent.put("buttons", buttons);
                components.add(buttonsComponent);
            }

            if (Boolean.TRUE.equals(req.getLimitedTimeOfferEnabled())) {
                Map<String, Object> lto = new LinkedHashMap<>();
                lto.put("type", "LIMITED_TIME_OFFER");
                lto.put("limited_time_offer", Map.of(
                        "text", req.getLimitedTimeOfferText(),
                        "has_expiration", Boolean.TRUE.equals(req.getLimitedTimeOfferHasExpiration())));
                components.add(lto);
            }

            if (isCarousel) {
                List<Map<String, Object>> cards = req.getCarouselCards().stream()
                        .map(this::buildCarouselCard)
                        .collect(Collectors.toList());
                Map<String, Object> carousel = new LinkedHashMap<>();
                carousel.put("type", "CAROUSEL");
                carousel.put("cards", cards);
                components.add(carousel);
            }
        }

        if ("NAMED".equals(req.getParameterFormat())) convertNamedExamples(components);
        return components;
    }

    @SuppressWarnings("unchecked")
    private void convertNamedExamples(List<Map<String, Object>> components) {
        for (var component : components) {
            String type = (String) component.get("type");
            if ("CAROUSEL".equals(type)) {
                for (var card : (List<Map<String, Object>>) component.get("cards"))
                    convertNamedExamples((List<Map<String, Object>>) card.get("components"));
                continue;
            }
            if (!(component.get("example") instanceof Map<?, ?> example)) continue;
            String key = "HEADER".equals(type) ? "header_text" : "body_text";
            if (!example.containsKey(key)) continue;
            List<String> values = "HEADER".equals(type) ? (List<String>) example.get(key) : ((List<List<String>>) example.get(key)).get(0);
            var names = WhatsAppTemplateValidation.variableNames((String) component.get("text"));
            List<Map<String, String>> samples = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) samples.add(Map.of("param_name", names.get(i), "example", values.get(i)));
            component.put("example", Map.of(key + "_named_params", samples));
        }
    }

    private void addHeaderComponent(List<Map<String, Object>> components, String headerType, String headerText,
                                     String headerExampleValue, String headerMediaHandle) {
        String type = headerType == null ? "NONE" : headerType;
        if ("NONE".equals(type)) return;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "HEADER");
        switch (type) {
            case "TEXT" -> {
                header.put("format", "TEXT");
                header.put("text", headerText);
                if (headerExampleValue != null && !headerExampleValue.isBlank()) {
                    header.put("example", Map.of("header_text", List.of(headerExampleValue)));
                }
            }
            case "IMAGE", "VIDEO", "DOCUMENT" -> {
                header.put("format", type);
                if (headerMediaHandle == null || headerMediaHandle.isBlank()) {
                    throw new WhatsAppApiException("Upload sample media for the " + type.toLowerCase() + " header before submitting this template.");
                }
                header.put("example", Map.of("header_handle", List.of(headerMediaHandle)));
            }
            case "LOCATION" -> header.put("format", "LOCATION");
            default -> throw new WhatsAppApiException("Unknown header type: " + type);
        }
        components.add(header);
    }

    private Map<String, Object> buildButton(WhatsAppTemplateCreateRequest.TemplateButton b) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("type", b.getType());
        switch (b.getType()) {
            case "QUICK_REPLY" -> btn.put("text", b.getText());
            case "PHONE_NUMBER" -> {
                btn.put("text", b.getText());
                btn.put("phone_number", b.getPhoneNumber());
            }
            case "URL" -> {
                btn.put("text", b.getText());
                btn.put("url", b.getUrl());
                if (b.getUrlExampleValue() != null && !b.getUrlExampleValue().isBlank()) {
                    btn.put("example", List.of(b.getUrlExampleValue()));
                }
            }
            case "COPY_CODE" -> btn.put("example", b.getCopyCodeExample());
            case "FLOW" -> {
                btn.put("text", b.getText());
                btn.put("flow_id", b.getFlowId());
                btn.put("flow_action", b.getFlowAction() == null ? "navigate" : b.getFlowAction());
                if (b.getNavigateScreen() != null && !b.getNavigateScreen().isBlank()) btn.put("navigate_screen", b.getNavigateScreen());
            }
            case "OTP" -> {
                btn.put("otp_type", b.getOtpType() != null ? b.getOtpType() : "COPY_CODE");
                if (b.getText() != null && !b.getText().isBlank()) btn.put("text", b.getText());
            }
            default -> throw new WhatsAppApiException("Unknown button type: " + b.getType());
        }
        return btn;
    }

    private Map<String, Object> buildCarouselCard(WhatsAppTemplateCreateRequest.CarouselCard card) {
        List<Map<String, Object>> cardComponents = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "HEADER");
        header.put("format", card.getHeaderType());
        if (card.getHeaderMediaHandle() == null || card.getHeaderMediaHandle().isBlank()) {
            throw new WhatsAppApiException("Upload sample media for every carousel card before submitting this template.");
        }
        header.put("example", Map.of("header_handle", List.of(card.getHeaderMediaHandle())));
        cardComponents.add(header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", card.getBodyText());
        if (card.getBodyExampleValues() != null && !card.getBodyExampleValues().isEmpty()) {
            body.put("example", Map.of("body_text", List.of(card.getBodyExampleValues())));
        }
        cardComponents.add(body);

        if (card.getButtons() != null && !card.getButtons().isEmpty()) {
            List<Map<String, Object>> buttons = card.getButtons().stream()
                    .map(this::buildButton)
                    .collect(Collectors.toList());
            Map<String, Object> buttonsComponent = new LinkedHashMap<>();
            buttonsComponent.put("type", "BUTTONS");
            buttonsComponent.put("buttons", buttons);
            cardComponents.add(buttonsComponent);
        }

        Map<String, Object> cardMap = new LinkedHashMap<>();
        cardMap.put("components", cardComponents);
        return cardMap;
    }

    private WhatsAppTemplateDto mapToDto(JsonNode t) {
        return WhatsAppTemplateDto.builder()
                .id(t.has("id") ? t.get("id").asText() : null)
                .name(t.has("name") ? t.get("name").asText() : null)
                .status(t.has("status") ? t.get("status").asText() : null)
                .category(t.has("category") ? t.get("category").asText() : null)
                .correctCategory(t.hasNonNull("correct_category") ? t.get("correct_category").asText() : null)
                .rejectedReason(t.hasNonNull("rejected_reason") ? t.get("rejected_reason").asText() : null)
                .parameterFormat(t.path("parameter_format").asText("POSITIONAL"))
                .language(t.has("language") ? t.get("language").asText() : null)
                .qualityScore(t.has("quality_score") && t.get("quality_score").has("score") ? t.get("quality_score").get("score").asText() : null)
                .components(t.get("components"))
                .build();
    }
}
