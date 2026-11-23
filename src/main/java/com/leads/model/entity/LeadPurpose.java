package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "lead_purposes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadPurpose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false, length = 120)
    private String purposeName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private com.leads.model.enums.PurposeClassification classification = com.leads.model.enums.PurposeClassification.POTENTIAL;

    @Column(length = 255)
    private String description;

    @Builder.Default
    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
