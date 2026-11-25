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

    /** Module 05 P1: a product used by a lead or a quotation item is deactivated, not deleted (plain FKs, no cascade). */
    @org.springframework.data.jpa.repository.Query("select count(lp) > 0 from LeadProduct lp where lp.product.id = :productId")
    boolean usedByLeads(@org.springframework.data.repository.query.Param("productId") Long productId);

    @org.springframework.data.jpa.repository.Query("select count(qi) > 0 from QuotationItem qi where qi.product.id = :productId")
    boolean usedByQuotations(@org.springframework.data.repository.query.Param("productId") Long productId);
}
