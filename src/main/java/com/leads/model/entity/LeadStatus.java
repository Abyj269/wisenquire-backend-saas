package com.leads.model.entity;

import com.leads.model.enums.StageType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "lead_statuses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false, length = 100)
    private String statusName;

    @Column(nullable = false, length = 50)
    private String statusCode;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String colorCode = "#6366F1"; // Default Indigo Hex

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StageType stageType;

    @Builder.Default
    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isDefault = false;

    /** True for the company's seeded pipeline stages — those can be renamed
     *  or recolored but never deleted; only statuses a user added later can be. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean isSystemDefined = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
