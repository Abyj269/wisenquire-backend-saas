package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "whatsapp_conversations", indexes = {
        @Index(name = "idx_conv_company_branch_lastmsg", columnList = "company_id, branch_id, last_message_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    // Which of the branch's (possibly several) connected WhatsApp numbers this
    // conversation came in on — a branch can now own more than one number, so
    // branch alone no longer disambiguates which chat belongs to which number.
    @Column(length = 100)
    private String receivingPhoneNumberId;

    @Column(nullable = false, length = 50)
    private String customerWaId; // Phone number with country code e.g. "971501234567"

    @Column(length = 150)
    private String customerName;

    // LAZY — the conversation-list queries in WhatsAppConversationRepository
    // use JOIN FETCH to load these in the same query instead, so switching
    // this to EAGER would reintroduce N+1 selects on every list load.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead; // Linked lead when created from WhatsApp chat

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_agent_id")
    private User assignedAgent;

    @Column(length = 500)
    private String lastMessageText;

    private LocalDateTime lastMessageAt;
    private Long lastCustomerMessageEpoch;

    // Which agent most recently sent an outbound message here — set only by
    // a real agent send (sendMessage/sendMediaMessage), never by automated
    // auto-replies, so it always names an actual person. Not touched when
    // the customer sends a new inbound message.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_message_sent_by_user_id")
    private User lastMessageSentBy;

    @Builder.Default
    @Column(nullable = false)
    private Integer unreadCount = 0;

    @Column(nullable = false)
    private boolean pinned;
    @Column(nullable = false)
    private boolean manuallyUnread;

    @Builder.Default
    @Column(nullable = false, length = 30)
    private String chatStatus = "OPEN"; // OPEN, RESOLVED, PENDING

    // Mirrored from the first "Click to WhatsApp Ad" referral message this
    // conversation ever received, so the conversation list can show an ad
    // badge without opening the chat.
    @Builder.Default
    @Column(nullable = false)
    private Boolean isFromAd = false;

    @Column(length = 255)
    private String adHeadline;

    @Column(length = 500)
    private String adSourceUrl;

    // Mirrored the same way as adHeadline/adSourceUrl, same "ad" vs "post"
    // gating as WhatsAppMessage.adSourceId — see that field's Javadoc.
    @Column(length = 100)
    private String adSourceId;

    // Mirrored from the first ad-referral message, same as adHeadline/
    // adSourceUrl — NOT gated to "ad"-only referrals (unlike adSourceId),
    // since ctwa_clid is meaningful for both "ad" and "post" referral types
    // and a lead should carry it regardless of whether the Ads module can
    // resolve a specific Ad row for it.
    @Column(length = 255)
    private String ctwaClid;

    // Throttle for WhatsAppScheduleService-driven auto-replies — at most one
    // per conversation per (company-local) calendar day, regardless of how
    // many messages the customer sends.
    private LocalDateTime lastAutoReplyAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    @JsonIgnore
    private List<WhatsAppMessage> messages = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
