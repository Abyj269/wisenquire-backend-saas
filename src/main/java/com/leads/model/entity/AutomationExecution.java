package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.ExecutionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "automation_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_id", nullable = false)
    @JsonIgnore
    private Automation automation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @JsonIgnore
    private Customer customer;

    @Column(name = "customer_phone", nullable = false, length = 30)
    private String customerPhone;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.RUNNING;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AutomationExecutionStep> steps = new ArrayList<>();
}
