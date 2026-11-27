package com.leads.service;

import com.leads.model.entity.AdAccountConnection;
import com.leads.repository.AdAccountConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates ad-account syncing across every active {@link
 * AdAccountConnection}. Each connection's actual work happens on {@link
 * AdSyncConnectionService} (a separate transactional bean — self-invocation
 * would not proxy {@code @Transactional} correctly) inside its own
 * try/catch, so one connection's failure (revoked token, network blip)
 * never blocks the rest of the run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdSyncService {
    private final TenantAccess access;

    private final AdAccountConnectionRepository connectionRepo;
    private final AdSyncConnectionService syncConnectionService;

    @Scheduled(cron = "${ads.sync.cron:0 15 * * * *}")
    // Hourly: one instance runs it; the lock is held at least 1 min so a second instance's trigger
    // at the same minute is skipped (01 Part B item 7).
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = com.leads.jobs.JobLocks.ADS_SYNC, lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void runScheduledSync() {
        com.leads.jobs.JobRuns.record(com.leads.jobs.ScheduledJob.ADS_SYNC);
        // Module 19 P1 (F-3 / job context): each connection runs as its own company, with orgId in the log context.
        for (Object[] scope : connectionRepo.findActiveScopes()) {
            Long connectionId = (Long) scope[0];
            TenantAccess.asCompanyJob((Long) scope[1], (Long) scope[2], () -> connectionRepo.findById(connectionId).ifPresent(connection -> {
                try {
                    syncConnectionService.syncConnection(connection);
                } catch (Exception ex) {
                    log.warn("Ad sync failed for connection {} ({} / {}): {}",
                            connection.getId(), connection.getProvider(), connection.getExternalAccountId(), ex.getMessage());
                    syncConnectionService.markFailed(connection.getId(), ex.getMessage());
                }
            }));
        }
    }

    /** Manual "Sync Now" — single connection, synchronous, so the admin UI gets immediate feedback. Rethrows on failure (after recording it) so the controller surfaces a real error. */
    public void syncNow(Long connectionId) {
        access.record(com.leads.model.entity.AdAccountConnection.class, connectionId);
        AdAccountConnection connection = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Ad account connection not found: " + connectionId));
        try {
            syncConnectionService.syncConnection(connection);
        } catch (RuntimeException ex) {
            syncConnectionService.markFailed(connectionId, ex.getMessage());
            throw ex;
        }
    }

    /** Historical backfill on a newly-created connection (or on explicit request) — async since a multi-year daily fetch shouldn't block the HTTP request that triggered it. */
    /**
     * Internal: callers check scope first (AdAccountConnectionService.requestBackfill, or a just-saved connection).
     * Module 19 P1: runs as the connection's company, with orgId in the log context.
     */
    @Async("adsSyncExecutor")
    public void backfillAsync(Long connectionId) {
        List<Object[]> scope = connectionRepo.findScope(connectionId);
        if (scope.isEmpty()) return;
        TenantAccess.asCompanyJob((Long) scope.get(0)[0], (Long) scope.get(0)[1], () -> connectionRepo.findById(connectionId).ifPresent(connection -> {
            try {
                syncConnectionService.backfill(connection);
            } catch (Exception ex) {
                log.warn("Ad backfill failed for connection {} ({} / {}): {}",
                        connection.getId(), connection.getProvider(), connection.getExternalAccountId(), ex.getMessage());
                syncConnectionService.markFailed(connection.getId(), ex.getMessage());
            }
        }));
    }
}
