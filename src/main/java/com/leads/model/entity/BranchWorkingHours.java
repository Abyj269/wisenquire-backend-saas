package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/** One row per (branch, day-of-week). A branch with no rows at all simply never participates in working/non-working auto-replies. */
@Entity
@Table(name = "whatsapp_business_hours", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "day_of_week"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchWorkingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    @JsonIgnore
    private Branch branch;

    /** 1=Monday .. 7=Sunday (ISO-8601 DayOfWeek.getValue() convention). columnDefinition matches the migration's TINYINT — without it, ddl-auto:validate expects INTEGER and fails to boot. */
    @Column(name = "day_of_week", nullable = false, columnDefinition = "TINYINT")
    private Integer dayOfWeek;

    @Builder.Default
    @Column(name = "is_open", nullable = false)
    private Boolean isOpen = true;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;
}
