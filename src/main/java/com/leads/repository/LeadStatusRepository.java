package com.leads.repository;

import com.leads.model.entity.LeadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadStatusRepository extends JpaRepository<LeadStatus, Long> {
    List<LeadStatus> findByCompanyIdOrderBySortOrderAsc(Long companyId);
    List<LeadStatus> findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(Long companyId);
    Optional<LeadStatus> findByCompanyIdAndIsDefaultTrue(Long companyId);
    Optional<LeadStatus> findByCompanyIdAndStatusCode(Long companyId, String statusCode);
}
