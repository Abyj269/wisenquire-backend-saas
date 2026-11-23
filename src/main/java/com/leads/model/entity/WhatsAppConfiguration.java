package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "whatsapp_configurations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    // Branch-wise, not company-wide — and a branch may connect more than one
    // number (e.g. a sales line and a support line), so this is a plain
    // many-to-one rather than unique.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    @JsonIgnore
    private Branch branch;

    // Globally unique on Meta's side — this is what a webhook payload carries,
    // so it's the reliable way to resolve exactly which number a message
    // belongs to regardless of how many numbers its branch has.
    @Column(nullable = false, length = 100, unique = true)
    private String phoneNumberId;

    @Column(nullable = false, length = 100)
    private String wabaId; // WhatsApp Business Account ID

    // The Meta App the access token belongs to — only needed for the
    // Resumable Upload API (/{app-id}/uploads) that template Image/Video/
    // Document headers and Carousel cards use to get a header_handle for
    // their sample media. Not secret, so no masking on read like appSecret.
    @Column(length = 100)
    private String appId;

    @Convert(converter = com.leads.util.WhatsAppCredentialEncryptor.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    @JsonIgnore
    private String accessToken; // Graph API token

    @Column(nullable = false, length = 100)
    private String verifyToken; // Webhook verification token

    @Convert(converter = com.leads.util.WhatsAppCredentialEncryptor.class)
    @Column(columnDefinition = "TEXT")
    @JsonIgnore
    private String appSecret;

    @Column(length = 50)
    private String displayPhoneNumber;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
