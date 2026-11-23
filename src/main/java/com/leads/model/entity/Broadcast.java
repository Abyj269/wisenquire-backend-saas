package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.BroadcastAudienceType;
import com.leads.model.enums.BroadcastStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "broadcasts", indexes = {
        @Index(name = "idx_broadcast_company_branch", columnList = "company_id, branch_id"),
        @Index(name = "idx_broadcast_status", columnList = "status"),
        @Index(name = "idx_broadcast_scheduled", columnList = "scheduled_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "whatsapp_configuration_id")
    private WhatsAppConfiguration whatsappConfiguration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "template_name", nullable = false, length = 150)
    private String templateName;

    @Column(name = "template_language", length = 20)
    private String templateLanguage;

    @Column(name = "template_components_json", columnDefinition = "LONGTEXT")
    private String templateComponentsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 50)
    private BroadcastAudienceType audienceType;

    @Column(name = "audience_filter_json", columnDefinition = "LONGTEXT")
    private String audienceFilterJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private BroadcastStatus status = BroadcastStatus.DRAFT;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_recipients")
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(name = "sent_count")
    @Builder.Default
    private Integer sentCount = 0;

    @Column(name = "delivered_count")
    @Builder.Default
    private Integer deliveredCount = 0;

    @Column(name = "read_count")
    @Builder.Default
    private Integer readCount = 0;

    @Column(name = "failed_count")
    @Builder.Default
    private Integer failedCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
