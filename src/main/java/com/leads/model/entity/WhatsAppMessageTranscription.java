package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * On-demand transcription of an inbound AUDIO WhatsApp message (voice note),
 * cached forever after first request — same shape and immutability rationale
 * as {@link WhatsAppMessageTranslation}. Provider support is uneven across
 * vendors (only OpenAI has a real transcription API today), so {@code provider}
 * here just records which one actually produced this row.
 */
@Entity
@Table(name = "whatsapp_message_transcriptions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_whatsapp_message_transcriptions_message", columnNames = "message_id")
})
public class WhatsAppMessageTranscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    @JsonIgnore
    private WhatsAppMessage message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String transcriptText;

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

    public Long getId() { return id; }
    public WhatsAppMessage getMessage() { return message; }
    public void setMessage(WhatsAppMessage message) { this.message = message; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getTranscriptText() { return transcriptText; }
    public void setTranscriptText(String transcriptText) { this.transcriptText = transcriptText; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public User getGeneratedByUser() { return generatedByUser; }
    public void setGeneratedByUser(User generatedByUser) { this.generatedByUser = generatedByUser; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
