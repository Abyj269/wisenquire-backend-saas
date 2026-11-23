package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.ActivityType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "lead_timeline_activities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadTimelineActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    @JsonIgnore
    private Lead lead;

    /** Nullable — only set for delivery-related activity rows, so a Lead's several deliveries over time can be queried individually. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id")
    @JsonIgnore
    private Delivery delivery;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityType activityType;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    // See Lead.dynamicValues for why there's no @JdbcTypeCode(SqlTypes.JSON)
    // here despite the DB column being declared JSON.
    @Column(columnDefinition = "json")
    private String metaData; // JSON string with old/new values

    /** Original filename of a note's attached file, if any. */
    @Column(length = 255)
    private String attachmentName;

    /** The attachment's contents as a data: URI (small files only — this is a demo-scale note attachment, not a file storage service). */
    @Column(columnDefinition = "TEXT")
    private String attachmentData;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "performed_by_id")
    private User performedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
