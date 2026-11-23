package com.leads.repository;

import com.leads.model.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    List<Company> findByOrganizationId(Long organizationId);
    Optional<Company> findByOrganizationIdAndCode(Long organizationId, String code);
    boolean existsByOrganizationIdAndCode(Long organizationId, String code);
}
