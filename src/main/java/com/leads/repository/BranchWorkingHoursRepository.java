package com.leads.repository;

import com.leads.model.entity.BranchWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchWorkingHoursRepository extends JpaRepository<BranchWorkingHours, Long> {
    List<BranchWorkingHours> findByBranchIdOrderByDayOfWeek(Long branchId);

    Optional<BranchWorkingHours> findByBranchIdAndDayOfWeek(Long branchId, Integer dayOfWeek);
}
