package com.leads.service;

import com.leads.model.enums.BroadcastStatus;
import com.leads.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls for SCHEDULED broadcasts that are due and dispatches them.
 *
 * ──────────────────────────────────────────────────────────────────
 * WHY THIS CLASS LOOKS THE WAY IT DOES
 * ──────────────────────────────────────────────────────────────────
 *
 * 1. NO @Transactional on the scheduler method
 *    sendBroadcast() opens its own @Transactional and registers an
 *    afterCommit() hook to submit work to the broadcastExecutor pool.
 *    If we put @Transactional here, Spring PROPAGATION_REQUIRED would
 *    merge all the sendBroadcast() calls into the scheduler's outer TX —
 *    the afterCommit() hook would then only fire once the entire loop
 *    finishes, causing race conditions. Each broadcast must commit
 *    independently.
 *
 * 2. TenantAccess.inCompany(companyId, work) is REQUIRED
 *    Scheduler threads have NO Spring Security context. sendBroadcast()
 *    calls access.permission("BROADCASTS:SEND"), which internally calls
 *    SecurityContextHolder.getAuthentication() — null on a scheduler
 *    thread — and throws AccessDeniedException, silently eating every
 *    scheduled broadcast without sending anything.
 *
 *    TenantAccess.inCompany() sets a ThreadLocal (SYSTEM_COMPANY) that
 *    causes permission() to return immediately (line 43 of TenantAccess:
 *    "if (SYSTEM_COMPANY.get() != null) return;"), bypassing the auth
 *    check while still scoping to the correct company's data.
 *
 *    This is identical to how the webhook processor and reconciler work.
 *
 * 3. findDueScheduledBroadcastIdAndCompanyId returns scalar ID pairs
 *    We cannot call b.getCompany().getId() on entities returned by
 *    findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull() because
 *    company is @ManyToOne(fetch = LAZY) and the entities are detached
 *    once the repository method returns (no @Transactional wrapper here).
 *    The scalar JPQL query avoids all proxy/lazy issues.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastSchedulerService {

    private final BroadcastRepository broadcastRepo;
    private final BroadcastService broadcastService;

    @Scheduled(fixedDelayString = "${broadcast.scheduler.check-ms:60000}", initialDelayString = "${broadcast.scheduler.initial-delay-ms:0}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = com.leads.jobs.JobLocks.BROADCAST_SCHEDULER, lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void processScheduledBroadcasts() {
        com.leads.jobs.JobRuns.record(com.leads.jobs.ScheduledJob.BROADCAST_SCHEDULER);
        LocalDateTime now = LocalDateTime.now();

        // Returns List<Object[]> where each row = [broadcastId (Long), companyId (Long)]
        // Using scalar projection avoids any lazy-load issues on detached entities.
        List<Object[]> due = broadcastRepo.findDueScheduledBroadcastIdAndCompanyId(
                BroadcastStatus.SCHEDULED, now);

        if (due.isEmpty()) return;

        log.info("[BROADCAST SCHEDULER] Found {} due scheduled broadcasts at {}", due.size(), now);

        for (Object[] row : due) {
            Long broadcastId = (Long) row[0];
            Long companyId   = (Long) row[1];

            log.info("[BROADCAST SCHEDULER] Triggering broadcast ID {} (companyId={})", broadcastId, companyId);
            try {
                // Wrap in inCompany() to set ThreadLocal SYSTEM_COMPANY so that
                // TenantAccess.permission() returns immediately instead of throwing
                // AccessDeniedException (scheduler thread has no SecurityContext).
                TenantAccess.inCompany(companyId, () ->
                        broadcastService.sendBroadcast(broadcastId));
            } catch (Exception ex) {
                log.error("[BROADCAST SCHEDULER] Failed to trigger broadcast ID {}: {}",
                        broadcastId, ex.getMessage(), ex);
            }
        }
    }
}
