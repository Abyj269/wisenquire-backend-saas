package com.leads.repository;

import com.leads.model.entity.BranchTrustedIp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BranchTrustedIpRepository extends JpaRepository<BranchTrustedIp, Long> {
    List<BranchTrustedIp> findByBranchIdOrderByIdAsc(Long branchId);
    List<BranchTrustedIp> findByBranchIdInAndIsActiveTrue(List<Long> branchIds);
}
