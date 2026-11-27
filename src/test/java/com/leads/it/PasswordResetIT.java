package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 02 P2 (Step 6.1): password reset. The request always answers 202 and emails a link only to an active
 * account, after the transaction committed (no transaction open during the send); the link works once, within
 * 30 minutes; a reset ends every session of the user.
 */
class PasswordResetIT extends AbstractIntegrationTest {

    record Sent(String to, String link, boolean txOpen, String thread) { }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<Sent> outbox = new ArrayBlockingQueue<>(10);
    private Long userId;
    private String address;

    @BeforeEach
    void setUp() {
        userId = fixture.freshUser(Slot.A1a);
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", encoder.encode("Old-Password-1"), userId);
        address = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        doAnswer(inv -> {
            outbox.add(new Sent(inv.getArgument(0), inv.getArgument(1), TransactionSynchronizationManager.isActualTransactionActive(),
                    Thread.currentThread().getName()));
            return null;
        }).when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
    }

    private int requestReset(String emailAddress) throws Exception {
        return mvc.perform(post("/public/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", emailAddress)))).andReturn().getResponse().getStatus();
    }

    private MockHttpServletResponse confirm(String token, String password) throws Exception {
        return mvc.perform(post("/public/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "newPassword", password)))).andReturn().getResponse();
    }

    private int login(String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", address, "password", password)))).andReturn().getResponse().getStatus();
    }

    private String tokenFrom(Sent sent) {
        String q = sent.link().substring(sent.link().indexOf("token=") + 6);
        return URLDecoder.decode(q, StandardCharsets.UTF_8);
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        assertEquals(202, requestReset("nobody-" + fixture.phone() + "@nowhere.test"));
        assertNull(outbox.poll(1, TimeUnit.SECONDS), "no email for an unknown address");
    }

    @Test
    void aResetLinkWorksOnceAndEndsEverySession() throws Exception {
        String oldAccess = fixture.token('A', com.leads.it.fixture.Persona.ORG_ADMIN); // another user's token is unaffected
        int beforeVersion = jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, userId);

        assertEquals(202, requestReset(address));
        Sent sent = outbox.poll(10, TimeUnit.SECONDS);
        assertNotNull(sent, "a link was emailed");
        assertEquals(address, sent.to());
        assertFalse(sent.txOpen(), "no transaction is open while the email is sent (hard rule 2)");
        assertTrue(sent.thread().startsWith("mail-"), "sent on the mail executor, after the request answered: " + sent.thread());
        assertTrue(sent.link().contains("/reset-password?token="), sent.link());
        String token = tokenFrom(sent);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM password_resets WHERE token_hash = ?", Integer.class, token), "only a hash is stored");

        MockHttpServletResponse weak = confirm(token, "short");
        assertEquals(400, weak.getStatus());
        assertEquals("VALIDATION_FAILED", json.readTree(weak.getContentAsString()).path("code").asText());

        assertEquals(204, confirm(token, "New-Password-1").getStatus());
        assertEquals(200, login("New-Password-1"), "the new password works");
        assertNotEquals(200, login("Old-Password-1"), "the old one does not");
        assertEquals(beforeVersion + 1, jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, userId),
                "every access token of the user is ended");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId));
        assertEquals(200, mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + oldAccess)).andReturn().getResponse().getStatus());

        MockHttpServletResponse again = confirm(token, "Another-Password-1");
        assertEquals(400, again.getStatus(), "single use");
    }

    @Test
    void anExpiredOrSupersededLinkIsRefused() throws Exception {
        assertEquals(202, requestReset(address));
        String first = tokenFrom(outbox.poll(10, TimeUnit.SECONDS));
        assertEquals(202, requestReset(address));
        String second = tokenFrom(outbox.poll(10, TimeUnit.SECONDS));
        assertEquals(400, confirm(first, "New-Password-1").getStatus(), "a newer request supersedes the earlier link");

        jdbc.update("UPDATE password_resets SET expires_at = expires_at - INTERVAL 31 MINUTE WHERE user_id = ? AND used_at IS NULL", userId);
        assertEquals(400, confirm(second, "New-Password-1").getStatus(), "expired after 30 minutes");
        assertEquals(400, confirm("not-a-real-token", "New-Password-1").getStatus());
    }

    @Test
    void anInactiveAccountGetsNoEmail() throws Exception {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", userId);
        assertEquals(202, requestReset(address));
        assertNull(outbox.poll(1, TimeUnit.SECONDS), "no email for an inactive account");
    }
}
