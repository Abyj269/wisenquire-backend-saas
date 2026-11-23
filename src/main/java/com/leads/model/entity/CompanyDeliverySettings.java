package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_delivery_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDeliverySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    @JsonIgnore
    private Company company;

    /** Master toggle — the whole Delivery module is invisible/inactive for a company until this is true. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean deliveryModuleEnabled = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean requireApprovalForPostpone = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean requireApprovalForClose = true;

    /** Soft/advisory only — surfaced to the approver, never enforced as a hard block. */
    private Integer maxDeliveryAttempts;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
