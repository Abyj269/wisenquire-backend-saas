package com.leads.dto.whatsapp;

import com.leads.model.enums.MessageDirection;
import com.leads.model.enums.MessageStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppMessageDto {
    private Long id;
    private Long conversationId;
    private String wamid;
    private MessageDirection direction;
    private String messageType;
    private String bodyText;
    private String mediaUrl;
    private String mediaMimeType;
    private String mediaFileName;
    private String mediaId;
    private Double latitude;
    private Double longitude;
    private String locationName;
    private String locationAddress;
    // A single "contacts" message can carry several vCards — the raw
    // structured data lives in WhatsAppMessage.contactsJson and is parsed
    // into this list by WhatsAppCloudService.mapMessageToDto.
    private List<WhatsAppContactDto> contacts;
    private String reactionEmoji;
    private String reactionTargetWamid;
    private String contextWamid;
    private Boolean isFromAd;
    private String adHeadline;
    private String adSourceUrl;
    private String adSourceType;
    private String ctwaClid;
    // Meta's referral.source_id — only ever non-null when adSourceType is
    // "ad" (never for a "post" referral, whose source_id isn't a real ad
    // id). The join key the Ads module uses to resolve which synced Ad this
    // message/conversation came from.
    private String adSourceId;
    private MessageStatus status;
    private String errorMessage;
    private Long sentByUserId;
    private String sentByUserName;
    private Boolean isAutoReply;
    /** Non-null when this outbound TEMPLATE message was sent via a Broadcast campaign. */
    private Long broadcastId;
    // ISO 639-1 code, or null if never detected (see WhatsAppMessage.detectedLanguage) —
    // drives the chat UI's translate-icon visibility (shown when set and not "en").
    private String detectedLanguage;
    private LocalDateTime createdAt;

    // Pass-through of the sender's optimistic-UI correlation id (see
    // SendMessageRequest.clientMessageId) — not a persisted column, only set
    // on the DTO returned/broadcast for a message this call just sent.
    private String clientMessageId;
}
