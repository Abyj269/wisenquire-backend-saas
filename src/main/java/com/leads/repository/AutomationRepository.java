package com.leads.repository;

import com.leads.model.entity.Automation;
import com.leads.model.enums.AutomationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationRepository extends JpaRepository<Automation, Long>, JpaSpecificationExecutor<Automation> {

    Page<Automation> findByCompanyIdAndDeletedAtIsNull(Long companyId, Pageable pageable);

    Page<Automation> findByCompanyIdAndBranchIdAndDeletedAtIsNull(Long companyId, Long branchId, Pageable pageable);

    Page<Automation> findByCompanyIdAndStatusAndDeletedAtIsNull(Long companyId, AutomationStatus status, Pageable pageable);

    Page<Automation> findByCompanyIdAndBranchIdAndStatusAndDeletedAtIsNull(Long companyId, Long branchId, AutomationStatus status, Pageable pageable);

    List<Automation> findByCompanyIdAndStatusAndDeletedAtIsNull(Long companyId, AutomationStatus status);

    Optional<Automation> findByIdAndDeletedAtIsNull(Long id);

    // Atomic counter bump: concurrent executions of the same automation must
    // not lose increments, which a load-modify-save of the entity would.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Automation a SET a.triggerCount = COALESCE(a.triggerCount, 0) + 1, " +
           "a.successCount = COALESCE(a.successCount, 0) + :success, " +
           "a.failureCount = COALESCE(a.failureCount, 0) + :failure, " +
           "a.lastTriggeredAt = :at WHERE a.id = :id")
    int recordRun(@Param("id") Long id, @Param("success") int success, @Param("failure") int failure, @Param("at") LocalDateTime at);
}
