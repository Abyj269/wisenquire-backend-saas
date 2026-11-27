package com.leads.it;

import com.leads.it.fixture.Slot;
import com.leads.mail.EmailOutbox;
import com.leads.mail.MailTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 6.2 (module 02 P2 item 4): the email outbox sends with no transaction open and only if the causing transaction
 * committed, retries with backoff and gives up after 6 attempts, never sends to a suppressed address, and the signed
 * bounce webhook suppresses an address. The login OTP is still sent synchronously, after the login transaction.
 */
class EmailOutboxIT extends AbstractIntegrationTest {

    @Autowired EmailOutbox outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.leads.controller.EmailWebhookController webhook;

    private String address() { return "outbox-" + fixture.phone() + "@it.test"; }

    private String status(String to) {
        return jdbc.queryForObject("SELECT status FROM email_outbox WHERE to_address = ? ORDER BY id DESC LIMIT 1", String.class, to);
    }

    private void enqueue(String to) {
        new TransactionTemplate(transactions).executeWithoutResult(st ->
                outbox.enqueue(to, MailTemplate.VERIFY_EMAIL, Map.of("link", "https://app.example.test/verify?x=1", "minutes", 60)));
    }

    @Test
    void anEmailIsSentAfterCommitWithNoTransactionOpen() {
        String to = address();
        AtomicBoolean txOpen = new AtomicBoolean(true);
        doAnswer(inv -> { txOpen.set(TransactionSynchronizationManager.isActualTransactionActive()); return null; })
                .when(mailProvider).send(argThat(m -> m.to().equals(to)));
        enqueue(to);

        outbox.sendDue();

        assertEquals("SENT", status(to));
        assertFalse(txOpen.get(), "no transaction is open during the send (hard rule 2)");
        verify(mailProvider).send(argThat(m -> m.to().equals(to) && m.html().contains("Confirm email") && m.text().contains("https://app.example.test/verify?x=1")));
    }

    @Test
    void nothingIsQueuedWhenTheCausingTransactionRollsBack() {
        String to = address();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(st -> {
            outbox.enqueue(to, MailTemplate.VERIFY_EMAIL, Map.of("link", "https://x.test", "minutes", 5));
            throw new IllegalStateException("the change failed");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE to_address = ?", Integer.class, to));
    }

    @Test
    void aFailingSendIsRetriedWithBackoffAndFailsAfterSixAttempts() {
        String to = address();
        doThrow(new IllegalStateException("smtp down")).when(mailProvider).send(argThat(m -> m.to().equals(to)));
        enqueue(to);
        for (int attempt = 1; attempt <= 6; attempt++) {
            outbox.sendDue();
            int attempts = jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE to_address = ?", Integer.class, to);
            assertEquals(attempt, attempts);
            if (attempt < 6) {
                assertEquals("PENDING", status(to));
                assertTrue(jdbc.queryForObject("SELECT next_attempt_at > created_at FROM email_outbox WHERE to_address = ?", Boolean.class, to), "backoff");
                outbox.sendDue();  // not due yet: nothing happens
                assertEquals(attempt, jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE to_address = ?", Integer.class, to));
                jdbc.update("UPDATE email_outbox SET next_attempt_at = created_at WHERE to_address = ?", to); // make it due
            }
        }
        assertEquals("FAILED", status(to));
        assertEquals("smtp down", jdbc.queryForObject("SELECT last_error FROM email_outbox WHERE to_address = ?", String.class, to));
    }

    @Test
    void anEmailLeftSendingByAStoppedNodeIsRetried() {
        String to = address();
        enqueue(to);
        jdbc.update("UPDATE email_outbox SET status = 'SENDING', claimed_at = created_at - INTERVAL 11 MINUTE WHERE to_address = ?", to);
        outbox.sendDue();
        assertEquals("SENT", status(to));
    }

    private String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private int postBounce(String body, String signature) throws Exception {
        var req = post("/email/webhook/bounce").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req.header("X-Signature", signature);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void aSignedHardBounceSuppressesTheAddress() throws Exception {
        String to = address();
        String body = "{\"email\":\"" + to.toUpperCase() + "\",\"type\":\"hard_bounce\"}";
        Object previous = ReflectionTestUtils.getField(webhook, "secret");
        try {
            ReflectionTestUtils.setField(webhook, "secret", "");
            assertEquals(401, postBounce(body, sign(body, "anything")), "off without a configured secret");
            ReflectionTestUtils.setField(webhook, "secret", "it-webhook-secret");
            assertEquals(401, postBounce(body, null), "unsigned");
            assertEquals(401, postBounce(body, sign(body, "wrong-secret")), "wrong signature");
            assertEquals(204, postBounce(body, sign(body, "it-webhook-secret")));
        } finally {
            ReflectionTestUtils.setField(webhook, "secret", previous);
        }
        enqueue(to);
        outbox.sendDue();
        assertEquals("SUPPRESSED", status(to), "no email to a hard-bounced address (matched case-insensitively)");
        verify(mailProvider, never()).send(argThat(m -> m.to().equals(to)));
    }

    @Test
    void theLoginOtpIsStillSentSynchronouslyAfterTheLoginTransaction() throws Exception {
        Long user = fixture.freshUser(Slot.A1a);
        jdbc.update("UPDATE users SET password = ?, is_trusted_user = 1 WHERE id = ?", encoder.encode("Otp-Pass-1"), user);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
        AtomicBoolean txOpen = new AtomicBoolean(true);
        AtomicInteger otpRows = new AtomicInteger(-1);
        doAnswer(inv -> {
            txOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
            otpRows.set(jdbc.queryForObject("SELECT COUNT(*) FROM login_otps WHERE user_id = ?", Integer.class, user));
            return null;
        }).when(mailProvider).send(argThat(m -> m.to().equals(email)));

        var r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Otp-Pass-1\"}")).andReturn().getResponse();

        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("\"otpRequired\":true"), r.getContentAsString());
        verify(mailProvider).send(argThat(m -> m.to().equals(email) && m.subject().contains("login")));
        assertFalse(txOpen.get(), "the OTP send runs after the login transaction (hard rule 2)");
        assertEquals(1, otpRows.get(), "the OTP was already committed when it was sent");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE to_address = ?", Integer.class, email), "the OTP never goes through the outbox");
    }

    @Test
    void aFailedOtpSendIsReportedToTheUser() throws Exception {
        Long user = fixture.freshUser(Slot.A1a);
        jdbc.update("UPDATE users SET password = ?, is_trusted_user = 1 WHERE id = ?", encoder.encode("Otp-Pass-2"), user);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
        doThrow(new IllegalStateException("smtp down")).when(mailProvider).send(argThat(m -> m.to().equals(email)));
        var r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Otp-Pass-2\"}")).andReturn().getResponse();
        assertNotEquals(200, r.getStatus(), "the user is told the code could not be delivered: " + r.getContentAsString());
        verify(mailProvider, atLeastOnce()).send(any());
    }
}
