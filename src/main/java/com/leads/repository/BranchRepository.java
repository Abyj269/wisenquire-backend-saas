package com.leads.repository;

import com.leads.model.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {
    List<Branch> findByCompanyId(Long companyId);
    List<Branch> findByCompanyIdIn(List<Long> companyIds);
    Optional<Branch> findByCompanyIdAndCode(Long companyId, String code);
}
