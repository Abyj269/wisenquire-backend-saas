package com.leads.repository;

import com.leads.model.entity.ProductMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductMasterRepository extends JpaRepository<ProductMaster, Long> {
    List<ProductMaster> findByCompanyIdOrderByProductNameAsc(Long companyId);
    List<ProductMaster> findByCompanyIdAndIsActiveTrueOrderByProductNameAsc(Long companyId);
    Optional<ProductMaster> findByCompanyIdAndProductCode(Long companyId, String productCode);
}
