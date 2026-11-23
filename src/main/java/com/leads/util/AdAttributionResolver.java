package com.leads.util;

import com.leads.model.entity.Ad;

import java.util.Comparator;
import java.util.List;

/**
 * Picks exactly one {@link Ad} row when more than one candidate shares the
 * same {@code externalAdId} within a company — normally impossible (Meta/
 * Google ad ids are globally unique), but reachable via a misconfiguration
 * where the same underlying ad account ends up connected twice (e.g. set as
 * both the company-default connection and a branch override). Shared by
 * lead-creation-time linking ({@code LeadService.linkAdAndClid}) and the
 * periodic reconciliation sweep ({@code AdSyncConnectionService}) so the two
 * never disagree on which Ad a given lead should resolve to.
 * <p>
 * Priority: (1) an Ad whose branch matches the lead's own branch — a
 * branch-override account wins for that branch's leads; (2) else an Ad with
 * no branch (the company-wide default); (3) else the lowest id, purely to
 * be stable and reproducible run-over-run in the genuinely-degenerate
 * duplicate-connection case.
 */
public final class AdAttributionResolver {

    private AdAttributionResolver() {
    }

    public static Ad pickBestMatch(List<Ad> candidates, Long leadBranchId) {
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.stream()
                .min(Comparator
                        .comparing((Ad a) -> !(a.getBranch() != null && a.getBranch().getId().equals(leadBranchId)))
                        .thenComparing(a -> a.getBranch() != null)
                        .thenComparing(Ad::getId))
                .orElse(null);
    }
}
