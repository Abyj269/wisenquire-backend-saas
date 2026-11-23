package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** One row per company: the three common auto-reply messages (working hours / non-working hours / holiday), each independently toggled and each optionally carrying an image/video. */
@Entity
@Table(name = "whatsapp_auto_reply_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppAutoReplySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    @JsonIgnore
    private Company company;

    @Builder.Default
    @Column(name = "working_hours_enabled", nullable = false)
    private Boolean workingHoursEnabled = false;
    @Column(name = "working_hours_message", columnDefinition = "TEXT")
    private String workingHoursMessage;
    @Column(name = "working_hours_media_data", columnDefinition = "LONGTEXT")
    private String workingHoursMediaData;
    @Column(name = "working_hours_media_mime_type", length = 100)
    private String workingHoursMediaMimeType;

    @Builder.Default
    @Column(name = "non_working_hours_enabled", nullable = false)
    private Boolean nonWorkingHoursEnabled = false;
    @Column(name = "non_working_hours_message", columnDefinition = "TEXT")
    private String nonWorkingHoursMessage;
    @Column(name = "non_working_hours_media_data", columnDefinition = "LONGTEXT")
    private String nonWorkingHoursMediaData;
    @Column(name = "non_working_hours_media_mime_type", length = 100)
    private String nonWorkingHoursMediaMimeType;

    @Builder.Default
    @Column(name = "holiday_enabled", nullable = false)
    private Boolean holidayEnabled = false;
    @Column(name = "holiday_message", columnDefinition = "TEXT")
    private String holidayMessage;
    @Column(name = "holiday_media_data", columnDefinition = "LONGTEXT")
    private String holidayMediaData;
    @Column(name = "holiday_media_mime_type", length = 100)
    private String holidayMediaMimeType;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
