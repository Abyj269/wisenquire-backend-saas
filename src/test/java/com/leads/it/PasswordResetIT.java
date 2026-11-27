package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.it.fixture.Slot;
import com.leads.mail.EmailOutbox;
import com.leads.mail.MailProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 02 P2 (Steps 6.1, 6.2): password reset. The request always answers 202 and queues a link only for an active
 * account, in the same transaction as the token; the email-outbox job sends it with no transaction open; the link
 * works once, within 30 minutes; a reset ends every session of the user.
 */
class PasswordResetIT extends AbstractIntegrationTest {

    record Sent(String to, String text, boolean txOpen) { }

    private static final Pattern TOKEN = Pattern.compile("reset-password\\?token=([A-Za-z0-9_%\\-]+)");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailOutbox outbox;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Sent> sent = new ArrayList<>();
    private Long userId;
    private String address;

    @BeforeEach
    void setUp() {
        userId = fixture.freshUser(Slot.A1a);
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", encoder.encode("Old-Password-1"), userId);
        address = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        doAnswer(inv -> {
            MailProvider.OutgoingMail m = inv.getArgument(0);
            sent.add(new Sent(m.to(), m.text(), TransactionSynchronizationManager.isActualTransactionActive()));
            return null;
        }).when(mailProvider).send(any());
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

    /** Runs the outbox job and returns the reset emails sent to our address. */
    private List<Sent> deliver() {
        outbox.sendDue();
        return sent.stream().filter(s -> s.to().equals(address)).toList();
    }

    private static String tokenFrom(Sent s) {
        Matcher m = TOKEN.matcher(s.text());
        assertTrue(m.find(), s.text());
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private int queued(String emailAddress) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE to_address = ? AND template = 'PASSWORD_RESET'", Integer.class, emailAddress);
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String nobody = "nobody-" + fixture.phone() + "@nowhere.test";
        assertEquals(202, requestReset(nobody));
        assertEquals(0, queued(nobody), "nothing queued for an unknown address");
    }

    @Test
    void aResetLinkWorksOnceAndEndsEverySession() throws Exception {
        String otherUsersToken = fixture.token('A', com.leads.it.fixture.Persona.ORG_ADMIN);
        int beforeVersion = jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, userId);

        assertEquals(202, requestReset(address));
        assertEquals(1, queued(address), "queued in the outbox with the token");
        List<Sent> mails = deliver();
        assertEquals(1, mails.size(), "the outbox job sent the link");
        assertFalse(mails.get(0).txOpen(), "no transaction is open while the email is sent (hard rule 2)");
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM email_outbox WHERE to_address = ? AND template = 'PASSWORD_RESET'", String.class, address));
        String token = tokenFrom(mails.get(0));
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
        assertEquals(200, mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + otherUsersToken)).andReturn().getResponse().getStatus());

        assertEquals(400, confirm(token, "Another-Password-1").getStatus(), "single use");
    }

    @Test
    void anExpiredOrSupersededLinkIsRefused() throws Exception {
        assertEquals(202, requestReset(address));
        String first = tokenFrom(deliver().get(0));
        assertEquals(202, requestReset(address));
        List<Sent> mails = deliver();
        String second = tokenFrom(mails.get(mails.size() - 1));
        assertEquals(400, confirm(first, "New-Password-1").getStatus(), "a newer request supersedes the earlier link");

        jdbc.update("UPDATE password_resets SET expires_at = expires_at - INTERVAL 31 MINUTE WHERE user_id = ? AND used_at IS NULL", userId);
        assertEquals(400, confirm(second, "New-Password-1").getStatus(), "expired after 30 minutes");
        assertEquals(400, confirm("not-a-real-token", "New-Password-1").getStatus());
    }

    @Test
    void anInactiveAccountGetsNoEmail() throws Exception {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", userId);
        assertEquals(202, requestReset(address));
        assertEquals(0, queued(address), "nothing queued for an inactive account");
    }
}
