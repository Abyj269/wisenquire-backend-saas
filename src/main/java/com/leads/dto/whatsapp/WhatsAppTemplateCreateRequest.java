package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplateCreateRequest {
    @NotNull(message = "A WhatsApp number is required")
    private Long configId;

    @NotBlank(message = "Template name is required")
    private String name;

    @NotBlank(message = "Category is required")
    private String category; // MARKETING, UTILITY, AUTHENTICATION

    @NotBlank(message = "Language is required")
    private String language; // e.g. en_US
    @Builder.Default
    private String parameterFormat = "POSITIONAL";

    // --- Header (ignored for AUTHENTICATION templates) ---
    @Builder.Default
    private String headerType = "NONE"; // NONE, TEXT, IMAGE, VIDEO, DOCUMENT, LOCATION
    private String headerText;
    /** Sample value for the header text's {{1}}, if it has one. */
    private String headerExampleValue;
    /** header_handle returned by POST /whatsapp/templates/media, for IMAGE/VIDEO/DOCUMENT headers. */
    private String headerMediaHandle;

    // --- Body / Footer ---
    /** Ignored for AUTHENTICATION templates — Meta auto-generates the OTP body. */
    private String bodyText;
    /** One sample value per {{n}} placeholder in bodyText, in order — Meta requires an example when a body uses variables. */
    private List<String> bodyExampleValues;
    /** For non-AUTHENTICATION templates, plain footer text. */
    private String footerText;

    // --- AUTHENTICATION only ---
    private Boolean addSecurityRecommendation;
    /** Minutes until the OTP expires; shown in the footer. Null = no expiration note. */
    private Integer codeExpirationMinutes;

    private List<TemplateButton> buttons;

    // --- Limited-Time Offer (MARKETING only) ---
    private Boolean limitedTimeOfferEnabled;
    private String limitedTimeOfferText;
    private Boolean limitedTimeOfferHasExpiration;

    // --- Carousel (MARKETING/UTILITY only; empty/null = not a carousel) ---
    private List<CarouselCard> carouselCards;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TemplateButton {
        private String type; // QUICK_REPLY, PHONE_NUMBER, URL, COPY_CODE, FLOW, OTP
        private String text;
        private String url;
        /** Sample value for a dynamic URL's trailing {{1}}. */
        private String urlExampleValue;
        private String phoneNumber;
        /** Sample offer code for a COPY_CODE button. */
        private String copyCodeExample;
        private String flowId;
        private String flowAction;
        private String navigateScreen;
        /** AUTHENTICATION OTP button type — "COPY_CODE" is the only one currently offered. */
        private String otpType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CarouselCard {
        private String headerType; // IMAGE, VIDEO
        private String headerMediaHandle;
        private String bodyText;
        private List<String> bodyExampleValues;
        /** Max 2, QUICK_REPLY/URL/PHONE_NUMBER only. */
        private List<TemplateButton> buttons;
    }
}
