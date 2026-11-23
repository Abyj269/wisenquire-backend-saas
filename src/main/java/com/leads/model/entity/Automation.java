package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.AutomationStatus;
import com.leads.model.enums.TriggerType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "automations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Automation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Builder.Default
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    @JsonIgnore
    private Branch branch;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AutomationStatus status = AutomationStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 50)
    private TriggerType triggerType;

    @Column(name = "trigger_json", columnDefinition = "LONGTEXT")
    private String triggerJson;

    @Column(name = "conditions_json", columnDefinition = "LONGTEXT")
    private String conditionsJson;

    @Column(name = "actions_json", columnDefinition = "LONGTEXT")
    private String actionsJson;

    @Builder.Default
    @Column(nullable = false)
    private Integer priority = 100;

    @Builder.Default
    @Column(name = "stop_on_match", nullable = false)
    private Boolean stopOnMatch = true;

    @Builder.Default
    @Column(name = "trigger_count")
    private Integer triggerCount = 0;

    @Builder.Default
    @Column(name = "success_count")
    private Integer successCount = 0;

    @Builder.Default
    @Column(name = "failure_count")
    private Integer failureCount = 0;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    @JsonIgnore
    private User createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
