package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leads.model.enums.ExecutionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "automation_execution_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecutionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    @JsonIgnore
    private AutomationExecution execution;

    @Column(name = "node_type", nullable = false, length = 50)
    private String nodeType;

    @Column(name = "node_label", length = 200)
    private String nodeLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @CreationTimestamp
    @Column(name = "executed_at", updatable = false)
    private LocalDateTime executedAt;

    @Column(columnDefinition = "TEXT")
    private String detail;
}
