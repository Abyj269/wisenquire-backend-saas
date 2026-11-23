package com.leads.repository;

import com.leads.model.entity.LeadSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadSourceRepository extends JpaRepository<LeadSource, Long> {
    List<LeadSource> findByCompanyIdOrderBySortOrderAsc(Long companyId);
    List<LeadSource> findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(Long companyId);
    Optional<LeadSource> findByCompanyIdAndSourceCode(Long companyId, String sourceCode);
}
