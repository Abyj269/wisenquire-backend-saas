package com.leads.repository;

import com.leads.model.entity.AdAccountConnection;
import com.leads.model.enums.AdProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdAccountConnectionRepository extends JpaRepository<AdAccountConnection, Long> {
    List<AdAccountConnection> findByCompanyIdOrderByIdAsc(Long companyId);
    List<AdAccountConnection> findByBranchIdOrderByIdAsc(Long branchId);
    List<AdAccountConnection> findByIsActiveTrue();

    /** Module 19 P1: [connectionId, companyId, organizationId] of every active connection (scheduled sync). */
    @org.springframework.data.jpa.repository.Query("SELECT c.id, c.company.id, c.company.organization.id FROM AdAccountConnection c WHERE c.isActive = true ORDER BY c.id")
    List<Object[]> findActiveScopes();

    /** Module 19 P1: [companyId, organizationId] of one connection. */
    @org.springframework.data.jpa.repository.Query("SELECT c.company.id, c.company.organization.id FROM AdAccountConnection c WHERE c.id = :id")
    List<Object[]> findScope(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<AdAccountConnection> findByCompanyIdAndBranchIsNullAndProvider(Long companyId, AdProvider provider);
    Optional<AdAccountConnection> findByCompanyIdAndBranchIdAndProvider(Long companyId, Long branchId, AdProvider provider);
}
