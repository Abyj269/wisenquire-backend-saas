package com.leads.repository;

import com.leads.model.entity.Ad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdRepository extends JpaRepository<Ad, Long> {
    Optional<Ad> findByConnectionIdAndExternalAdId(Long connectionId, String externalAdId);

    // Used by lead-to-ad reconciliation: all Ad rows across every connection
    // this company owns that could match a given external ad id — the Java
    // side then picks exactly one (branch match preferred) per the
    // deterministic tie-break in AdSyncConnectionService.
    List<Ad> findByCompanyIdAndExternalAdId(Long companyId, String externalAdId);

    List<Ad> findByCompanyIdAndExternalCampaignId(Long companyId, String externalCampaignId);

    // Backs the ad-performance report's ad/campaign rollups: every ad
    // relevant to a branch filter is either that branch's own (an override
    // connection's ad) or company-wide-shared (branch IS NULL) — seeding
    // rows from Ad/AdInsight rather than from leads is what lets a Google
    // campaign with zero attributed leads still show real spend.
    @Query("SELECT a FROM Ad a WHERE a.company.id = :companyId AND (:branchId IS NULL OR a.branch.id = :branchId OR a.branch IS NULL)")
    List<Ad> findForReport(@Param("companyId") Long companyId, @Param("branchId") Long branchId);
}
