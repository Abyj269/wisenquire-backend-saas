package com.leads.it;

import com.leads.LeadsSystemApplication;
import com.leads.jobs.JobRuns;
import com.leads.jobs.ScheduledJob;
import com.leads.service.AdSyncService;
import com.leads.service.BroadcastSchedulerService;
import com.leads.service.WhatsAppReceiptService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 01 §7 "ShedLock: 2 app contexts against one DB; each job runs once." The shared IT context is
 * instance 1; this test starts a second full application context (instance 2) on the same MySQL.
 * Both instances fire each of the 4 jobs at the same moment; across both, exactly one body runs
 * (counted by JobRuns). The start-up credential migration ran exactly once in total too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoInstancesShedLockIT extends AbstractIntegrationTest {

    @Autowired ApplicationContext instance1;
    @Autowired JdbcTemplate jdbc;
    private ConfigurableApplicationContext instance2;

    @BeforeAll
    void startSecondInstance() {
        fixture.ensureBuilt();
        instance2 = new SpringApplicationBuilder(LeadsSystemApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--spring.profiles.active=it",
                        "--server.port=0", "--server.address=127.0.0.1", "--management.server.port=-1",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true",
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver");
    }

    @AfterAll
    void stopSecondInstance() { if (instance2 != null) instance2.close(); }

    @Test
    void theCredentialMigrationRanOnceAcrossInstances() {
        assertEquals(1, JobRuns.count(ScheduledJob.WHATSAPP_CREDENTIAL_MIGRATION), "start-up migration bodies run in this JVM");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tasks WHERE task_key = 'whatsapp-credential-migration'", Integer.class));
    }

    @Test
    void adSyncRunsOnce() throws Exception {
        firesOnce(ScheduledJob.ADS_SYNC, AdSyncService.class, AdSyncService::runScheduledSync);
    }

    @Test
    void broadcastSchedulerRunsOnce() throws Exception {
        firesOnce(ScheduledJob.BROADCAST_SCHEDULER, BroadcastSchedulerService.class, BroadcastSchedulerService::processScheduledBroadcasts);
    }

    @Test
    void broadcastRecoveryRunsOnce() throws Exception { // module 16 P1
        firesOnce(ScheduledJob.BROADCAST_RECOVERY, BroadcastSchedulerService.class, BroadcastSchedulerService::recoverStalledBroadcasts);
    }

    @Test
    void emailOutboxRunsOnce() throws Exception { // Step 6.2
        firesOnce(ScheduledJob.EMAIL_OUTBOX, com.leads.mail.EmailOutbox.class, com.leads.mail.EmailOutbox::run);
    }

    @Test
    void receiptReconcileRunsOnce() throws Exception {
        firesOnce(ScheduledJob.WHATSAPP_RECEIPT_RECONCILE, WhatsAppReceiptService.class, WhatsAppReceiptService::reconcile);
    }

    @Test
    void receiptCleanupRunsOnce() throws Exception {
        firesOnce(ScheduledJob.WHATSAPP_RECEIPT_CLEANUP, WhatsAppReceiptService.class, WhatsAppReceiptService::cleanup);
    }

    /** Both instances call the (ShedLock-proxied) job at the same instant; exactly one body runs. */
    private <T> void firesOnce(ScheduledJob job, Class<T> type, Consumer<T> call) throws Exception {
        jdbc.update("DELETE FROM shedlock WHERE name = ?", job.lockName); // release the start-up run's lock
        long before = JobRuns.count(job);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> runs = new java.util.ArrayList<>();
            for (ApplicationContext ctx : List.of(instance1, instance2)) {
                T bean = ctx.getBean(type);
                runs.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    call.accept(bean);
                    return null;
                }));
            }
            for (Future<Object> f : runs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, JobRuns.count(job) - before, job + " bodies run across 2 instances");
    }
}
