package com.leads.dto.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppWebhookPayload {
    private String object;
    private List<Entry> entry;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private String id;
        private List<Change> changes;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        private Value value;
        private String field;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        @JsonProperty("messaging_product")
        private String messagingProduct;
        private Metadata metadata;
        private List<Contact> contacts;
        private List<Message> messages;
        private List<Status> statuses;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        @JsonProperty("display_phone_number")
        private String displayPhoneNumber;
        @JsonProperty("phone_number_id")
        private String phoneNumberId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        private Profile profile;
        @JsonProperty("wa_id")
        private String waId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String from;
        private String id;
        private String timestamp;
        private String type; // text, image, video, audio, document, sticker, location, contacts, interactive, button, reaction, order, system, unsupported
        private Text text;
        private Image image;
        private Video video;
        private Audio audio;
        private Document document;
        private Sticker sticker;
        private Location location;
        private List<MessageContact> contacts;
        private Interactive interactive;
        private ButtonReply button;
        private Reaction reaction;
        // Quoted-reply metadata AND, on the first inbound message of an
        // ad-originated conversation, the "Click to WhatsApp Ad" details —
        // Meta nests referral under context on some API versions and as a
        // sibling of context on others, so both are captured (see Context).
        private Context context;
        private Referral referral;
        // Present when type=unsupported (and occasionally alongside other
        // types) - Meta's own explanation for why it couldn't relay the
        // message content, e.g. code 131051 "Message type unknown" for a
        // WhatsApp client feature (poll, view-once media, ...) the Cloud API
        // doesn't yet support. See populateInboundContent's fallback.
        private List<MessageError> errors;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageError {
        private Integer code;
        private String title;
        private String message;
        private ErrorData error_data;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorData {
        private String details;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Video {
        private String id;
        private String mime_type;
        private String sha256;
        private String caption;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sticker {
        private String id;
        private String mime_type;
        private String sha256;
        private Boolean animated;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {
        private Double latitude;
        private Double longitude;
        private String name;
        private String address;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageContact {
        private ContactName name;
        private List<ContactPhone> phones;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContactName {
        @JsonProperty("formatted_name")
        private String formattedName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContactPhone {
        private String phone;
        private String type;
    }

    // Customer tapped a button/list option on a message WE sent (interactive
    // template reply) — button_reply and list_reply share the same shape.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Interactive {
        private String type; // button_reply, list_reply
        @JsonProperty("button_reply")
        private InteractiveReply buttonReply;
        @JsonProperty("list_reply")
        private InteractiveReply listReply;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InteractiveReply {
        private String id;
        private String title;
        private String description;
    }

    // Customer tapped a quick-reply button on a legacy template message.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ButtonReply {
        private String text;
        private String payload;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reaction {
        @JsonProperty("message_id")
        private String messageId;
        private String emoji; // empty string means the reaction was removed
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Context {
        private String id; // wamid of the quoted/replied-to message
        private String from;
        private Referral referral;
    }

    // "Click to WhatsApp Ad" / ad-originated conversation details.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Referral {
        @JsonProperty("source_url")
        private String sourceUrl;
        @JsonProperty("source_type")
        private String sourceType; // ad, post
        @JsonProperty("source_id")
        private String sourceId;
        private String headline;
        private String body;
        @JsonProperty("media_type")
        private String mediaType;
        @JsonProperty("ctwa_clid")
        private String ctwaClid;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Text {
        private String body;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Image {
        private String id;
        private String mime_type;
        private String sha256;
        private String caption;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Document {
        private String id;
        private String filename;
        private String mime_type;
        private String caption;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Audio {
        private String id;
        private String mime_type;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        private String id;
        private String status; // sent, delivered, read, failed
        private String timestamp;
        @JsonProperty("recipient_id")
        private String recipientId;
        private List<StatusError> errors;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusError {
        private Integer code;
        private String title;
        private String message;
        @JsonProperty("error_data")
        private StatusErrorData errorData;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusErrorData { private String details; }
}
