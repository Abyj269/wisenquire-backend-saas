package com.leads.dto.whatsapp;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConversationDto {
    private boolean pinned;
    private boolean manuallyUnread;
    private Long id;
    private Long companyId;
    private Long branchId;
    private String branchName;
    private String customerWaId;
    private String customerName;
    private Long leadId;
    private String leadNumber;
    private Boolean leadIsClosed;
    private com.leads.model.enums.PurposeClassification leadPurposeClassification;
    /** Who the linked lead itself is assigned to — distinct from assignedAgentId/Name below, which is this WhatsApp conversation's own (currently unused in the UI) assignment. */
    private Long leadAssignedToUserId;
    private String leadAssignedToName;
    private Long customerId;
    private Long assignedAgentId;
    private String assignedAgentName;
    private String lastMessageText;
    private LocalDateTime lastMessageAt;
    /** The agent who most recently sent an outbound message here (never an automated auto-reply) — "who's been handling this customer", not a formal assignment. */
    private String lastMessageSentByName;
    private Long lastCustomerMessageEpoch;
    private java.time.Instant replyWindowExpiresAt;
    private java.time.Instant serverTime;
    private Boolean freeFormAllowed;
    private Integer unreadCount;
    private String chatStatus;
    private Boolean isFromAd;
    private String adHeadline;
    private String adSourceUrl;
    private List<WhatsAppMessageDto> recentMessages;
}
