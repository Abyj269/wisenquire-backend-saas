package com.leads.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.service.CompanyClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Module 02 P2 item 4: the transactional email outbox (V62).
 * <ul>
 *   <li>{@link #enqueue} writes the email in the CALLER's transaction, so it exists only if the change that causes it
 *       commits (e.g. a password-reset token).</li>
 *   <li>The {@code email-outbox} job claims due emails with {@code FOR UPDATE SKIP LOCKED} in a short transaction,
 *       then renders and sends each with NO transaction open (CLAUDE.md hard rule 2), then records the result.</li>
 *   <li>A failure is retried after 1 min, 5 min, 15 min, 1 h and 6 h; after 6 attempts the email is FAILED. An address in
 *       {@code email_suppressions} is never sent to (SUPPRESSED). An email left SENDING by a stopped node is retried
 *       after 10 minutes (at least once).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutbox {

    static final int MAX_ATTEMPTS = 6;
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));
    static final int BATCH = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MailRenderer renderer;
    private final MailProvider provider;
    private final EmailSuppressions suppressions;
    private final CompanyClock clock;
    private final PlatformTransactionManager transactions;

    /** Queues an email; call it inside the transaction of the change that causes it. */
    public void enqueue(String to, MailTemplate template, Map<String, ?> variables) {
        if (to == null || to.isBlank()) throw new IllegalArgumentException("An email needs an address.");
        for (String name : template.variables) {
            if (variables == null || variables.get(name) == null) throw new IllegalArgumentException(template + " needs " + name);
        }
        try {
            LocalDateTime now = clock.serverNow();
            jdbc.update("INSERT INTO email_outbox (to_address, template, variables_json, status, attempts, next_attempt_at, created_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                    to.trim(), template.name(), mapper.writeValueAsString(variables), Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Email variables are not serialisable.", e);
        }
    }

    private record Claimed(long id, String to, String template, String variablesJson, int attempts) { }

    @Scheduled(fixedDelayString = "${email.outbox.check-ms:10000}", initialDelayString = "${email.outbox.initial-delay-ms:10000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = com.leads.jobs.JobLocks.EMAIL_OUTBOX, lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        com.leads.jobs.JobRuns.record(com.leads.jobs.ScheduledJob.EMAIL_OUTBOX);
        sendDue();
    }

    /** Sends every due email, in batches. Returns how many were handed to the provider successfully. */
    public int sendDue() {
        LocalDateTime now = clock.serverNow();
        new TransactionTemplate(transactions).executeWithoutResult(st -> jdbc.update(
                "UPDATE email_outbox SET status = 'PENDING', claimed_at = NULL WHERE status = 'SENDING' AND claimed_at < ?",
                Timestamp.valueOf(now.minusMinutes(10))));
        int sent = 0;
        while (true) {
            List<Claimed> batch = claim();
            if (batch.isEmpty()) return sent;
            for (Claimed c : batch) if (sendOne(c)) sent++;
        }
    }

    private List<Claimed> claim() {
        return new TransactionTemplate(transactions).execute(st -> {
            LocalDateTime now = clock.serverNow();
            List<Claimed> rows = jdbc.query("SELECT id, to_address, template, variables_json, attempts FROM email_outbox "
                            + "WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY id LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                    Timestamp.valueOf(now));
            for (Claimed c : rows) jdbc.update("UPDATE email_outbox SET status = 'SENDING', claimed_at = ? WHERE id = ?", Timestamp.valueOf(now), c.id());
            return rows;
        });
    }

    private boolean sendOne(Claimed c) {
        if (suppressions.isSuppressed(c.to())) {
            finish(c.id(), "SUPPRESSED", c.attempts(), null, "The address hard-bounced or complained earlier; not sent.");
            return false;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("An email must not be sent inside a DB transaction (hard rule 2).");
        try {
            Map<String, Object> vars = mapper.readValue(c.variablesJson(), new TypeReference<>() { });
            provider.send(renderer.render(c.to(), MailTemplate.valueOf(c.template()), vars));
            finish(c.id(), "SENT", c.attempts() + 1, null, null);
            return true;
        } catch (Exception e) {
            int attempts = c.attempts() + 1;
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (attempts >= MAX_ATTEMPTS) {
                finish(c.id(), "FAILED", attempts, null, error);
                log.warn("Email {} ({}) to {} failed after {} attempts: {}", c.id(), c.template(), mask(c.to()), attempts, error);
            } else {
                finish(c.id(), "PENDING", attempts, clock.serverNow().plus(BACKOFF.get(attempts - 1)), error);
            }
            return false;
        }
    }

    private void finish(long id, String status, int attempts, LocalDateTime nextAttempt, String error) {
        new TransactionTemplate(transactions).executeWithoutResult(st -> jdbc.update(
                "UPDATE email_outbox SET status = ?, attempts = ?, claimed_at = NULL, next_attempt_at = COALESCE(?, next_attempt_at), "
                        + "sent_at = CASE WHEN ? = 'SENT' THEN ? ELSE sent_at END, last_error = ? WHERE id = ?",
                status, attempts, nextAttempt == null ? null : Timestamp.valueOf(nextAttempt), status, Timestamp.valueOf(clock.serverNow()),
                error == null ? null : error.substring(0, Math.min(500, error.length())), id));
    }

    /** a***@example.com: addresses are personal data, so logs show only the first letter and the domain. */
    static String mask(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at <= 0 ? "***" : address.charAt(0) + "***" + address.substring(at).toLowerCase(Locale.ROOT);
    }
}
