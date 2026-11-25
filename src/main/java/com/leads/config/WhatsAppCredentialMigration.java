package com.leads.config;

import com.leads.jobs.JobLocks;
import com.leads.jobs.JobRuns;
import com.leads.jobs.ScheduledJob;
import com.leads.util.WhatsAppCredentialEncryptor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Encrypts any legacy plaintext WhatsApp credentials. Keyset batches keep it bounded.
 * <p>
 * 01 Part B item 7: runs ONCE per database instead of at every start-up. Guarded by
 * <ol>
 *   <li>a done-marker row in {@code one_time_tasks} (V53): once written, it never runs again;</li>
 *   <li>the ShedLock lock {@code whatsapp-credential-migration}: if two instances start together,
 *       only one migrates; the other skips (the marker is checked again inside the lock).</li>
 * </ol>
 * With ShedLock switched off ({@code app.shedlock.enabled=false}) the marker alone still prevents re-runs.
 * New credentials are always written encrypted by the JPA converter, so nothing is missed later.
 */
@Slf4j
@Component
public class WhatsAppCredentialMigration implements ApplicationRunner {

    static final String TASK_KEY = JobLocks.WHATSAPP_CREDENTIAL_MIGRATION;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectProvider<LockProvider> lockProvider;

    public WhatsAppCredentialMigration(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectProvider<LockProvider> lockProvider) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.lockProvider = lockProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (done()) return;
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) { runOnce(); return; }
        Optional<SimpleLock> lock = provider.lock(new LockConfiguration(Instant.now(), TASK_KEY, Duration.ofMinutes(30), Duration.ZERO));
        if (lock.isEmpty()) {
            log.info("WhatsApp credential migration is running on another instance; skipped here");
            return;
        }
        try {
            runOnce();
        } finally {
            lock.get().unlock();
        }
    }

    private void runOnce() {
        tx.executeWithoutResult(status -> {
            if (done()) return;
            JobRuns.record(ScheduledJob.WHATSAPP_CREDENTIAL_MIGRATION);
            int updated = migrate();
            jdbc.update("INSERT INTO one_time_tasks (task_key, completed_by) VALUES (?, ?)", TASK_KEY, host());
            log.info("WhatsApp credential migration done: {} value(s) encrypted; it will not run again", updated);
        });
    }

    private boolean done() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tasks WHERE task_key = ?", Integer.class, TASK_KEY);
        return n != null && n > 0;
    }

    private int migrate() {
        var converter = new WhatsAppCredentialEncryptor();
        long after = 0;
        int updated = 0;
        while (true) {
            var rows = jdbc.queryForList("SELECT id, access_token, app_secret FROM whatsapp_configurations WHERE id > ? ORDER BY id LIMIT 100", after);
            if (rows.isEmpty()) return updated;
            for (var row : rows) {
                after = ((Number) row.get("id")).longValue();
                for (String column : new String[]{"access_token", "app_secret"}) {
                    String old = (String) row.get(column);
                    if (old != null && !old.startsWith(WhatsAppCredentialEncryptor.PREFIX)) {
                        updated += jdbc.update("UPDATE whatsapp_configurations SET " + column + "=? WHERE id=? AND " + column + "=?",
                                converter.convertToDatabaseColumn(old), after, old);
                    }
                }
            }
        }
    }

    private static String host() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown"; }
    }
}
