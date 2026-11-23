package com.leads.model.entity;

import com.leads.model.enums.FieldType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "dynamic_field_definitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicFieldDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String module = "LEAD";

    @Column(nullable = false, length = 80)
    private String fieldName; // e.g. "budget_range", "preferred_language"

    @Column(nullable = false, length = 120)
    private String fieldLabel; // e.g. "Estimated Budget Range", "Preferred Contact Language"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FieldType fieldType;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isMandatory = false;

    @Column(length = 255)
    private String defaultValue;

    @Column(length = 255)
    private String placeholder;

    @Column(length = 255)
    private String regexValidation;

    @Column(columnDefinition = "TEXT")
    private String optionsJson; // JSON string array of options: [{"label":"VIP","value":"VIP"},{"label":"Standard","value":"STD"}]

    @Builder.Default
    @Column(nullable = false)
    private Integer gridWidth = 6; // Grid column span 1 to 12

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
