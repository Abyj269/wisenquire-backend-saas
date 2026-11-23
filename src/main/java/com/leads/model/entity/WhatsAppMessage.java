package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.MessageDirection;
import com.leads.model.enums.MessageStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "whatsapp_messages", indexes = {
        // id as a secondary key breaks ties deterministically — created_at
        // alone is not unique enough under rapid successive inserts (e.g. a
        // webhook burst), where multiple rows can land on the same
        // millisecond and make ORDER BY created_at alone non-deterministic.
        @Index(name = "idx_msg_conversation_created", columnList = "conversation_id, created_at, id"),
        @Index(name = "idx_msg_wamid", columnList = "wamid", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    @JsonIgnore
    private WhatsAppConversation conversation;

    @Column(length = 255)
    private String wamid; // WhatsApp Message ID from Meta
    @Column(length = 100)
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageDirection direction;

    @Builder.Default
    @Column(nullable = false, length = 30)
    private String messageType = "TEXT"; // TEXT, IMAGE, DOCUMENT, AUDIO, VIDEO, TEMPLATE

    // Set only when messageType is TEMPLATE — the Meta template name this
    // message was sent from, so usage can be counted per template/date-range
    // (see WhatsAppMessageRepository.countTemplateUsage).
    @Column(length = 512)
    private String templateName;
    @Column(length = 30)
    private String templateCategory;
    @Column(length = 20)
    private String templateLanguage;

    // Set when this outbound message was sent by WhatsAppScheduleService's
    // working-hours/non-working-hours/holiday logic rather than an agent —
    // distinguishes it in the chat UI and audit trail (isFromAd is the
    // inbound-side equivalent of a boolean provenance flag next to messageType).
    @Builder.Default
    @Column(nullable = false)
    private Boolean isAutoReply = false;

    @Column(columnDefinition = "TEXT")
    private String bodyText;

    // ISO 639-1 code (e.g. "ar", "fr", "en"), set once at inbound-webhook
    // ingestion time for direction=INBOUND + messageType=TEXT only, via a
    // free local heuristic (no AI call). NULL means detection never ran
    // (includes all historical rows — no backfill). Drives the chat UI's
    // translate-icon visibility: shown when set and not "en".
    @Column(length = 10)
    private String detectedLanguage;

    @Column(length = 500)
    private String mediaUrl;

    @Column(length = 100)
    private String mediaMimeType;

    @Column(length = 150)
    private String mediaFileName;

    // Meta's own media id from the webhook payload (or from our own upload
    // when this is an outbound media message) — resolved to actual bytes
    // lazily by WhatsAppMediaService on first view, not eagerly at webhook
    // time, since Meta's media URLs are short-lived signed links anyway.
    @Column(length = 150)
    private String mediaId;

    private Double latitude;
    private Double longitude;

    @Column(length = 255)
    private String locationName;

    @Column(length = 500)
    private String locationAddress;

    // JSON-serialized List<WhatsAppContactDto> — a single "contacts" message
    // can carry several vCards, so this needs to be a structured list rather
    // than a flattened name string (see WhatsAppCloudService.mapMessageToDto).
    @Column(columnDefinition = "TEXT")
    private String contactsJson;

    // Set when this row IS a reaction event (messageType REACTION) — the
    // emoji and the wamid of the message it reacts to. An empty emoji means
    // the customer removed a previously-sent reaction.
    @Column(length = 20)
    private String reactionEmoji;

    @Column(length = 150)
    private String reactionTargetWamid;

    // wamid of the message this one is a quoted reply to, if any.
    @Column(length = 150)
    private String contextWamid;

    // "Click to WhatsApp Ad" referral — present on the message that started
    // an ad-originated conversation.
    @Builder.Default
    @Column(nullable = false)
    private Boolean isFromAd = false;

    @Column(length = 255)
    private String adHeadline;

    @Column(length = 500)
    private String adSourceUrl;

    @Column(length = 30)
    private String adSourceType;

    @Column(length = 255)
    private String ctwaClid;

    // Meta's referral.source_id — but ONLY when source_type is "ad". A
    // boosted/organic "post" referral's source_id is a composite
    // {page_id}_{post_id}, not a numeric Meta ad id, and does not correspond
    // to anything in Ad.externalAdId, so it must never land here (it would
    // corrupt the lead-to-ad reconciliation join). isFromAd/adHeadline/
    // adSourceUrl/adSourceType are still set for both "ad" and "post" —
    // only this column is gated.
    @Column(length = 100)
    private String adSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    @Column(length = 255)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by_user_id")
    private User sentByUser;

    /** Set when this outbound TEMPLATE message was sent as part of a Broadcast campaign.
     *  NULL for all regular agent-sent or auto-reply messages. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Broadcast broadcast;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
