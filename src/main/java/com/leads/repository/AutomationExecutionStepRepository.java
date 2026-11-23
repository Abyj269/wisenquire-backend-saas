package com.leads.repository;

import com.leads.model.entity.AutomationExecutionStep;
import com.leads.model.enums.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationExecutionStepRepository extends JpaRepository<AutomationExecutionStep, Long> {

    List<AutomationExecutionStep> findByExecutionIdOrderByExecutedAtAsc(Long executionId);

    long countByExecutionAutomationIdAndNodeTypeAndStatus(Long automationId, String nodeType, ExecutionStatus status);
}
