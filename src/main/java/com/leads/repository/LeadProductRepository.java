package com.leads.repository;

import com.leads.model.entity.LeadProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadProductRepository extends JpaRepository<LeadProduct, Long> {
    List<LeadProduct> findByLeadId(Long leadId);
    void deleteByLeadId(Long leadId);
}
