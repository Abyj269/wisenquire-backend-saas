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
    Optional<AdAccountConnection> findByCompanyIdAndBranchIsNullAndProvider(Long companyId, AdProvider provider);
    Optional<AdAccountConnection> findByCompanyIdAndBranchIdAndProvider(Long companyId, Long branchId, AdProvider provider);
}
