package com.leads.service;

import com.leads.dto.whatsapp.WhatsAppTemplateCreateRequest;
import java.util.*;
import java.util.regex.Pattern;

/** Local validation of the editor's supported formats; Meta remains the approval authority. */
final class WhatsAppTemplateValidation {
    private WhatsAppTemplateValidation() {}
    static void validate(WhatsAppTemplateCreateRequest r) {
        require(r.getName() != null && r.getName().matches("[a-z0-9_]{1,512}"), "Use lowercase letters, numbers and underscores for the template name (max 512).");
        require(Set.of("MARKETING", "UTILITY", "AUTHENTICATION").contains(Objects.toString(r.getCategory(), "")), "Invalid template category.");
        require(r.getLanguage() != null && r.getLanguage().matches("[a-z]{2,3}(_[A-Za-z]{2,4})?"), "A valid Meta language code is required.");
        boolean auth = "AUTHENTICATION".equals(r.getCategory());
        require(Set.of("POSITIONAL", "NAMED").contains(Objects.toString(r.getParameterFormat(), "")), "Invalid parameter format.");
        var buttons = r.getButtons() == null ? List.<WhatsAppTemplateCreateRequest.TemplateButton>of() : r.getButtons();
        if (auth) {
            require(buttons.size() == 1 && "OTP".equals(buttons.get(0).getType()), "Authentication requires one OTP button.");
            require(r.getCodeExpirationMinutes() == null || r.getCodeExpirationMinutes() >= 1 && r.getCodeExpirationMinutes() <= 90, "Code expiration must be 1–90 minutes.");
            require(!Boolean.TRUE.equals(r.getLimitedTimeOfferEnabled()) && (r.getCarouselCards() == null || r.getCarouselCards().isEmpty()), "Authentication cannot include offers or carousel cards.");
        } else {
            text(r.getBodyText(), 1024, "Body");
            examples(r.getBodyText(), r.getBodyExampleValues(), "Body", r.getParameterFormat());
            if ("TEXT".equals(r.getHeaderType())) {
                text(r.getHeaderText(), 60, "Header");
                examples(r.getHeaderText(), r.getHeaderExampleValue() == null || r.getHeaderExampleValue().isBlank() ? List.of() : List.of(r.getHeaderExampleValue()), "Header", r.getParameterFormat());
            }
            require(r.getFooterText() == null || r.getFooterText().length() <= 60 && !r.getFooterText().contains("{{"), "Footer must be plain text of at most 60 characters.");
        }
        buttons(buttons, auth, 10);
        if (Boolean.TRUE.equals(r.getLimitedTimeOfferEnabled())) {
            require("MARKETING".equals(r.getCategory()), "Limited-time offers require Marketing category.");
            text(r.getLimitedTimeOfferText(), 16, "Offer text");
            require(r.getFooterText() == null || r.getFooterText().isBlank(), "Limited-time offers cannot include a footer.");
            require(r.getCarouselCards() == null || r.getCarouselCards().isEmpty(), "Choose a limited-time offer or a carousel, not both.");
        }
        if (r.getCarouselCards() != null && !r.getCarouselCards().isEmpty()) {
            var cards = r.getCarouselCards();
            require(cards.size() >= 2 && cards.size() <= 10, "A carousel requires 2–10 cards.");
            var first = cards.get(0);
            for (var card : cards) {
                require(card != null && Set.of("IMAGE", "VIDEO").contains(Objects.toString(card.getHeaderType(), "")), "Carousel headers must be images or videos.");
                require(card.getHeaderType().equals(first.getHeaderType()), "All carousel cards must use the same header format.");
                text(card.getBodyText(), 160, "Card body");
                examples(card.getBodyText(), card.getBodyExampleValues(), "Card body", r.getParameterFormat());
                var cardButtons = card.getButtons() == null ? List.<WhatsAppTemplateCreateRequest.TemplateButton>of() : card.getButtons();
                require(!cardButtons.isEmpty(), "Each carousel card requires a button.");
                buttons(cardButtons, false, 2);
                require(cardButtons.stream().allMatch(b -> Set.of("QUICK_REPLY", "URL", "PHONE_NUMBER").contains(b.getType())), "Unsupported carousel button type.");
                require(cardButtons.stream().map(WhatsAppTemplateCreateRequest.TemplateButton::getType).toList().equals(first.getButtons().stream().map(WhatsAppTemplateCreateRequest.TemplateButton::getType).toList()), "All cards must have matching button types and order.");
            }
        }
    }
    private static void buttons(List<WhatsAppTemplateCreateRequest.TemplateButton> buttons, boolean auth, int max) {
        require(buttons.size() <= max, "Too many buttons.");
        Map<String, Integer> counts = new HashMap<>();
        for (var b : buttons) {
            require(b != null && b.getType() != null, "Button type is required.");
            counts.merge(b.getType(), 1, Integer::sum);
            if (!"COPY_CODE".equals(b.getType()) && !"OTP".equals(b.getType())) text(b.getText(), 25, "Button label");
            switch (b.getType()) {
                case "URL" -> {
                    require(b.getUrl() != null && b.getUrl().matches("https?://[^\\s]+") && b.getUrl().length() <= 2000, "Button URL must be an HTTP(S) URL of at most 2000 characters.");
                    if (b.getUrl().contains("{{")) {
                        require(b.getUrl().endsWith("{{1}}") && b.getUrl().indexOf("{{") == b.getUrl().lastIndexOf("{{"), "Dynamic URLs require a single trailing {{1}}.");
                        require(b.getUrlExampleValue() != null && b.getUrlExampleValue().matches("https?://[^\\s]+"), "Provide a complete example URL for the dynamic URL button.");
                    }
                }
                case "PHONE_NUMBER" -> require(b.getPhoneNumber() != null && b.getPhoneNumber().matches("\\+[1-9][0-9]{6,14}"), "Use an international phone number beginning with +.");
                case "COPY_CODE" -> text(b.getCopyCodeExample(), 15, "Offer code example");
                case "FLOW" -> {
                    require(b.getFlowId() != null && b.getFlowId().matches("[0-9]+"), "Flow ID must be numeric.");
                    String action = b.getFlowAction() == null ? "navigate" : b.getFlowAction();
                    require(Set.of("navigate", "data_exchange").contains(action), "Invalid Flow action.");
                    if ("navigate".equals(action)) text(b.getNavigateScreen(), 100, "Flow starting screen");
                }
                case "OTP" -> require(auth && (b.getOtpType() == null || "COPY_CODE".equals(b.getOtpType())), "This editor supports copy-code authentication only.");
                case "QUICK_REPLY" -> require(!auth, "Authentication requires an OTP button.");
                default -> throw new IllegalArgumentException("Unsupported button type.");
            }
        }
        require(counts.getOrDefault("URL", 0) <= 2 && counts.getOrDefault("PHONE_NUMBER", 0) <= 1 && counts.getOrDefault("COPY_CODE", 0) <= 1 && counts.getOrDefault("FLOW", 0) <= 1, "Button type limit exceeded (2 URLs; 1 phone, offer code or Flow).");
    }
    static List<String> variableNames(String text) {
        var matcher = Pattern.compile("\\{\\{([^{}]+)}}").matcher(Objects.toString(text, ""));
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) names.add(matcher.group(1));
        return List.copyOf(names);
    }
    private static void examples(String text, List<String> values, String label, String format) {
        String remainder = text.replaceAll("\\{\\{[^{}]+}}", "");
        require(!remainder.contains("{{") && !remainder.contains("}}"), label + " contains an incomplete variable placeholder.");
        if ("NAMED".equals(format)) {
            var names = variableNames(text);
            require(names.stream().allMatch(n -> n.matches("[a-z][a-z0-9_]*")), "Named variables must use lowercase names such as {{customer_name}}.");
            require((values == null ? 0 : values.size()) == names.size() && (values == null || values.stream().allMatch(v -> v != null && !v.isBlank())), label + " needs one example per named variable, in order of first appearance.");
            return;
        }
        var matcher = Pattern.compile("\\{\\{([^{}]+)}}").matcher(text);
        Set<Integer> positions = new TreeSet<>();
        while (matcher.find()) {
            require(matcher.group(1).matches("[1-9][0-9]{0,2}"), label + " currently supports numbered variables such as {{1}}.");
            positions.add(Integer.valueOf(matcher.group(1)));
        }
        require(positions.isEmpty() || Collections.max(positions) == positions.size(), label + " variables must be consecutive, starting at {{1}}.");
        require((values == null ? 0 : values.size()) == positions.size() && (values == null || values.stream().allMatch(v -> v != null && !v.isBlank())), label + " needs exactly one nonempty example per variable.");
    }
    private static void text(String value, int max, String label) { require(value != null && !value.isBlank() && value.length() <= max, label + " is required and must be at most " + max + " characters."); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
