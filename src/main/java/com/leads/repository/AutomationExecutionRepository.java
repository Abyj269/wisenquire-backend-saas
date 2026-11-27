package com.leads.repository;

import com.leads.model.entity.AutomationExecution;
import com.leads.model.enums.ExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, Long> {

    Page<AutomationExecution> findByAutomationIdOrderByStartedAtDesc(Long automationId, Pageable pageable);

    Long countByAutomationId(Long automationId);

    /** Phase 1 close: an execution only through its own automation. */
    java.util.Optional<AutomationExecution> findByIdAndAutomationId(Long id, Long automationId);

    Long countByAutomationIdAndStatus(Long automationId, ExecutionStatus status);

    List<AutomationExecution> findByAutomationId(Long automationId);

    @Query("SELECT COUNT(DISTINCT e.customerPhone) FROM AutomationExecution e WHERE e.automation.id = :automationId")
    long countDistinctCustomers(@Param("automationId") Long automationId);
}
