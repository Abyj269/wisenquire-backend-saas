package com.leads.repository;

import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, Long> {
    List<AiProviderConfig> findByCompanyIdOrderByIdAsc(Long companyId);
    List<AiProviderConfig> findByBranchIdOrderByIdAsc(Long branchId);
    Optional<AiProviderConfig> findByBranchIdAndIsSelectedTrueAndIsActiveTrue(Long branchId);
    Optional<AiProviderConfig> findByBranchIdAndProvider(Long branchId, AiProviderType provider);
}
