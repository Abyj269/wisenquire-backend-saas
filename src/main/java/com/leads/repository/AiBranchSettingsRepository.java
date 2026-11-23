package com.leads.repository;

import com.leads.model.entity.AiBranchSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiBranchSettingsRepository extends JpaRepository<AiBranchSettings, Long> {
    Optional<AiBranchSettings> findByBranchId(Long branchId);
}
