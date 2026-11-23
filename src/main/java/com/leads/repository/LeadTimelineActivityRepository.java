package com.leads.repository;

import com.leads.model.entity.LeadTimelineActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadTimelineActivityRepository extends JpaRepository<LeadTimelineActivity, Long> {
    List<LeadTimelineActivity> findByLeadIdOrderByCreatedAtDesc(Long leadId);

    // Batched lookup for the Response Time report — one query for a whole
    // report's worth of leads instead of N+1 lazy loads of lead.activities.
    List<LeadTimelineActivity> findByLeadIdInOrderByCreatedAtAsc(List<Long> leadIds);

    List<LeadTimelineActivity> findByDeliveryIdOrderByCreatedAtDesc(Long deliveryId);
}
