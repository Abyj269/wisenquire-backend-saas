package com.leads.repository;

import com.leads.model.entity.AdInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdInsightRepository extends JpaRepository<AdInsight, Long> {
    Optional<AdInsight> findByAdIdAndStatDate(Long adId, LocalDate statDate);

    // Per-ad spend total for the ad-performance report: a null fromDate/toDate
    // means "lifetime to date" (no date filter at all), a non-null pair means
    // "spend in this date range" — same query shape either way, per AdInsight's
    // (ad_id, stat_date) daily grain. MAX(currency) is a cheap "pick any" —
    // an ad's currency comes from its ad account and doesn't change day to
    // day. Object[] rows are [adId (Long), spend (BigDecimal), currency (String)].
    @Query("SELECT i.ad.id, COALESCE(SUM(i.spend), 0), i.currency FROM AdInsight i " +
            "WHERE i.ad.company.id = :companyId AND (:branchId IS NULL OR i.ad.branch.id = :branchId OR i.ad.branch IS NULL) " +
            "AND (:fromDate IS NULL OR i.statDate >= :fromDate) AND (:toDate IS NULL OR i.statDate <= :toDate) " +
            "GROUP BY i.ad.id, i.currency")
    List<Object[]> sumSpendByAd(@Param("companyId") Long companyId, @Param("branchId") Long branchId,
                                 @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
}
