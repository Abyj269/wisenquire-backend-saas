package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** A date-range holiday for a company, applicable to whichever branches are linked. Its own message/media, if set, overrides the company's common holiday auto-reply just for these dates. */
@Entity
@Table(name = "whatsapp_holidays")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    @Column(name = "media_data", columnDefinition = "LONGTEXT")
    private String mediaData;

    @Column(name = "media_mime_type", length = 100)
    private String mediaMimeType;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "whatsapp_holiday_branches",
            joinColumns = @JoinColumn(name = "holiday_id"),
            inverseJoinColumns = @JoinColumn(name = "branch_id")
    )
    @OrderBy("id ASC")
    @Builder.Default
    private Set<Branch> branches = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
