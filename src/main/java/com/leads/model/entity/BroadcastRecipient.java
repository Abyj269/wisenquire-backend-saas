package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.BroadcastRecipientStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "broadcast_recipients", indexes = {
        @Index(name = "idx_bcast_recip_broadcast", columnList = "broadcast_id"),
        @Index(name = "idx_bcast_recip_wamid", columnList = "wamid"),
        @Index(name = "idx_bcast_recip_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id", nullable = false)
    @JsonIgnore
    private Broadcast broadcast;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @Column(name = "recipient_phone", nullable = false, length = 50)
    private String recipientPhone;

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Column(length = 255)
    private String wamid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private BroadcastRecipientStatus status = BroadcastRecipientStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
