package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * On-demand AI summary of a WhatsApp conversation thread, cached per
 * conversation (1:1) — gives a newly-assigned agent a 3-sentence catch-up
 * instead of scrolling a long thread. Reuses the same context-assembly +
 * usage-guard + provider-abstraction stack as conversion scoring.
 * Re-summarizing overwrites the previous row (unlike the translation cache,
 * a conversation's content keeps growing, so this is refreshable, not
 * immutable).
 */
@Entity
@Table(name = "whatsapp_conversation_summaries", uniqueConstraints = {
        @UniqueConstraint(name = "uq_whatsapp_conversation_summaries_conversation", columnNames = "conversation_id")
})
public class WhatsAppConversationSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    @JsonIgnore
    private WhatsAppConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(nullable = false)
    private Integer basedOnMessageCount;

    @Column(length = 20)
    private String provider;

    @Column(length = 100)
    private String modelName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_user_id", nullable = false)
    private User generatedByUser;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public WhatsAppConversation getConversation() { return conversation; }
    public void setConversation(WhatsAppConversation conversation) { this.conversation = conversation; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public Integer getBasedOnMessageCount() { return basedOnMessageCount; }
    public void setBasedOnMessageCount(Integer basedOnMessageCount) { this.basedOnMessageCount = basedOnMessageCount; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public User getGeneratedByUser() { return generatedByUser; }
    public void setGeneratedByUser(User generatedByUser) { this.generatedByUser = generatedByUser; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
