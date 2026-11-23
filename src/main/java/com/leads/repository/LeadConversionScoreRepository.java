package com.leads.repository;

import com.leads.model.entity.LeadConversionScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeadConversionScoreRepository extends JpaRepository<LeadConversionScore, Long> {
    Optional<LeadConversionScore> findByLeadId(Long leadId);
}
