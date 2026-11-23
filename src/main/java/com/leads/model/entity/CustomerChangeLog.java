package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.ActivityType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_change_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityType activityType;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

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
