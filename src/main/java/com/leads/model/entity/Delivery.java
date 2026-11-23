package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.DeliveryStatus;
import com.leads.model.enums.RequestedDeliveryAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    @JsonIgnore
    private Lead lead;

    @Column(nullable = false, unique = true, length = 50)
    private String deliveryNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryStatus status;

    @Column(nullable = false)
    private LocalDate scheduledDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    @Column(nullable = false, length = 150)
    private String recipientName;

    @Column(nullable = false, length = 50)
    private String recipientPhone;

    @Column(nullable = false, length = 255)
    private String addressLine1;

    @Column(length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String stateOrEmirate;

    @Column(length = 100)
    private String country;

    @Column(length = 20)
    private String postalCode;

    @Column(length = 255)
    private String landmark;

    /** Set when the address was picked via the map/search location picker at scheduling time — nullable, manual address entry stays fully supported. */
    @Column(precision = 10, scale = 7)
    private java.math.BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private java.math.BigDecimal longitude;

    @Column(columnDefinition = "TEXT")
    private String deliveryNotes; // staff instructions to the agent, set at scheduling time

    @Builder.Default
    @Column(nullable = false)
    private Integer attemptCount = 0;

    private LocalDateTime lastAttemptAt;

    private LocalDateTime deliveredAt;

    /** Text-only proof of delivery (no photo/signature — out of scope for v1). */
    @Column(columnDefinition = "TEXT")
    private String deliveryProofNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "failure_reason_id")
    private DeliveryFailureReason failureReason;

    @Column(columnDefinition = "TEXT")
    private String failureNotes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestedDeliveryAction requestedAction = RequestedDeliveryAction.NONE;

    private LocalDate requestedNewDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedByUser;

    private LocalDateTime requestedAt;

    @Column(columnDefinition = "TEXT")
    private String requestNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_decision_by_id")
    private User approvalDecisionBy;

    private LocalDateTime approvalDecisionAt;

    @Column(columnDefinition = "TEXT")
    private String approvalRejectionNotes;

    @Column(columnDefinition = "TEXT")
    private String cancelledReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
