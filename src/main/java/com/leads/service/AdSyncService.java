package com.leads.service;

import com.leads.model.entity.AdAccountConnection;
import com.leads.repository.AdAccountConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    public void runScheduledSync() {
        for (AdAccountConnection connection : connectionRepo.findByIsActiveTrue()) {
            try {
                syncConnectionService.syncConnection(connection);
            } catch (Exception ex) {
                log.warn("Ad sync failed for connection {} ({} / {}): {}",
                        connection.getId(), connection.getProvider(), connection.getExternalAccountId(), ex.getMessage());
                syncConnectionService.markFailed(connection.getId(), ex.getMessage());
            }
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
    @Async("adsSyncExecutor")
    public void backfillAsync(Long connectionId) {
        connectionRepo.findById(connectionId).ifPresent(connection -> {
            try {
                syncConnectionService.backfill(connection);
            } catch (Exception ex) {
                log.warn("Ad backfill failed for connection {} ({} / {}): {}",
                        connection.getId(), connection.getProvider(), connection.getExternalAccountId(), ex.getMessage());
                syncConnectionService.markFailed(connection.getId(), ex.getMessage());
            }
        });
    }
}
