package com.leads.repository;

import com.leads.model.entity.DeliveryFailureReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryFailureReasonRepository extends JpaRepository<DeliveryFailureReason, Long> {
    List<DeliveryFailureReason> findByCompanyIdOrderBySortOrderAsc(Long companyId);
    List<DeliveryFailureReason> findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(Long companyId);
}
