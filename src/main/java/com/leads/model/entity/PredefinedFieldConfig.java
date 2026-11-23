package com.leads.model.entity;

import com.leads.model.enums.PredefinedLeadField;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

// Per-company hide/mandatory toggle for a native (predefined) Lead field —
// NOT admin-extensible (see PredefinedLeadField, a closed enum). Rows for
// locked fields (FIRST_NAME/PHONE/PURPOSE/SOURCE) may still exist for
// uniformity, but PredefinedFieldService refuses to update them — locking is
// enforced in code, not by omitting the row.
@Entity
@Table(name = "predefined_field_configs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "field_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredefinedFieldConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_key", nullable = false, length = 40)
    private PredefinedLeadField fieldKey;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isVisible = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isMandatory = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
