package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_lead_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyLeadSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    @JsonIgnore
    private Company company;

    @Builder.Default
    @Column(nullable = false)
    private Boolean linkProductEnabled = true; // Setting to enable/disable product linking in leads

    @Builder.Default
    @Column(nullable = false)
    private Boolean autoAssignEnabled = false;

    @Column(length = 50)
    private String autoAssignStrategy; // e.g. "ROUND_ROBIN"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_status_id")
    private LeadStatus defaultStatus;

    /** Reports & Analytics: a customer with no new lead for this many days is
     *  considered "lost" — drives the Customers report's Lost/Regained split. */
    @Builder.Default
    @Column(nullable = false)
    private Integer customerLostAfterDays = 60;

    /** When true, an inbound WhatsApp message from a customer with no active
     *  lead auto-creates one (see WhatsAppCloudService#maybeAutoCreateLead).
     *  When false, lead creation from WhatsApp stays manual (the existing
     *  "Create Lead from Chat" button). */
    @Builder.Default
    @Column(nullable = false)
    private Boolean whatsappAutoCreateLeadEnabled = false;

    /**
     * Conflict resolution strategy when multiple active automations match an incoming message:
     * FIRST_MATCH (default) - execute highest-priority match and stop
     * RUN_ALL - execute all matching automations
     */
    @Builder.Default
    @Column(name = "automation_conflict_strategy", length = 30)
    private String automationConflictStrategy = "FIRST_MATCH";

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
