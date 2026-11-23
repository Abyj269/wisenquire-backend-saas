package com.leads.repository;

import com.leads.model.entity.LeadPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadPurposeRepository extends JpaRepository<LeadPurpose, Long> {
    List<LeadPurpose> findByCompanyIdOrderBySortOrderAsc(Long companyId);
    List<LeadPurpose> findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(Long companyId);
}
