package com.leads.repository;

import com.leads.model.entity.LeadLostReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadLostReasonRepository extends JpaRepository<LeadLostReason, Long> {
    List<LeadLostReason> findByCompanyIdOrderBySortOrderAsc(Long companyId);
    List<LeadLostReason> findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(Long companyId);
}
