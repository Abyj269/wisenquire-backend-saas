package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * On-demand English translation of one inbound WhatsApp message, cached
 * forever after first request. Unidirectional to {@link WhatsAppMessage} (no
 * back-reference needed) and 1:1 in practice via a unique FK. A separate
 * table rather than inline columns on WhatsAppMessage because translations
 * are sparse (most messages are agent-authored, already English, or
 * non-text) — inline nullable columns would bloat the largest table in the
 * schema for a minority-case feature. Immutable once written: messages never
 * change, so a translation is never regenerated, hence no updatedAt.
 */
@Entity
@Table(name = "whatsapp_message_translations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_whatsapp_message_translations_message", columnNames = "message_id")
})
public class WhatsAppMessageTranslation {

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

    /** Frozen copy of WhatsAppMessage.detectedLanguage at translation time. */
    @Column(nullable = false, length = 10)
    private String sourceLanguage;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String translatedText;

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
    public String getSourceLanguage() { return sourceLanguage; }
    public void setSourceLanguage(String sourceLanguage) { this.sourceLanguage = sourceLanguage; }
    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public User getGeneratedByUser() { return generatedByUser; }
    public void setGeneratedByUser(User generatedByUser) { this.generatedByUser = generatedByUser; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
