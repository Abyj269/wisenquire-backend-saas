package com.leads.repository;

import com.leads.model.entity.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long>, JpaSpecificationExecutor<Quotation> {

    @Query("SELECT COUNT(q) FROM Quotation q WHERE q.company.id = :companyId")
    long countByCompanyId(@Param("companyId") Long companyId);
}
