package com.leads.repository;

import com.leads.model.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {
    long countByCustomerId(Long customerId);

    // Backs TenantAccess.record(Customer.class, id) - a branch-restricted
    // caller may only open a customer that has at least one lead at one of
    // their permitted branches, matching the same-shaped scoping already
    // applied to the Customers list query.
    boolean existsByCustomerIdAndBranchIdIn(Long customerId, Collection<Long> branchIds);
    long countByCustomerIdAndIsConvertedTrue(Long customerId);
    Optional<Lead> findByLeadNumber(String leadNumber);
    Optional<Lead> findByCompanyIdAndPhone(Long companyId, String phone);

    List<Lead> findByCompanyId(Long companyId);
    Page<Lead> findByCompanyId(Long companyId, Pageable pageable);

    List<Lead> findByBranchId(Long branchId);
    Page<Lead> findByBranchId(Long branchId, Pageable pageable);

    Page<Lead> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    List<Lead> findTop1ByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @Query("SELECT DISTINCT l.branch FROM Lead l WHERE l.customer.id = :customerId")
    List<com.leads.model.entity.Branch> findDistinctBranchesByCustomerId(@Param("customerId") Long customerId);

    List<Lead> findByAssignedToId(Long userId);
    Page<Lead> findByAssignedToId(Long userId, Pageable pageable);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.company.id = :companyId")
    long countByCompanyId(@Param("companyId") Long companyId);

    long countByStatusId(Long statusId);

    long countBySourceId(Long sourceId);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.company.id = :companyId AND l.status.stageType = 'CONVERTED'")
    long countConvertedByCompanyId(@Param("companyId") Long companyId);

    @Query("SELECT l.status.statusName, COUNT(l) FROM Lead l WHERE l.company.id = :companyId GROUP BY l.status.statusName")
    List<Object[]> countLeadsByStatusForCompany(@Param("companyId") Long companyId);

    // WhatsApp auto-lead-creation: a customer already mid-pipeline elsewhere
    // shouldn't get a second, competing lead spawned just because they also
    // messaged in on WhatsApp.
    @Query("SELECT COUNT(l) > 0 FROM Lead l WHERE l.customer.id = :customerId AND l.status.stageType NOT IN ('CONVERTED', 'LOST', 'CLOSED')")
    boolean existsActiveLeadForCustomer(@Param("customerId") Long customerId);

    // Customer History modal (WhatsApp Inbox) — total income generated from this customer's converted leads.
    @Query("SELECT COALESCE(SUM(l.estimatedDealValue), 0) FROM Lead l WHERE l.customer.id = :customerId AND l.isConverted = true")
    java.math.BigDecimal sumConvertedDealValueByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.customer.id = :customerId AND l.status.stageType = 'LOST'")
    long countLostByCustomerId(@Param("customerId") Long customerId);
}
