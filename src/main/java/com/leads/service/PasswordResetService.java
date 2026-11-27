package com.leads.service;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;
import com.leads.model.entity.User;
import com.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Module 02 P2: self-service password reset (V61).
 * <ul>
 *   <li>{@link #request} always succeeds the same way (202), whether or not the email belongs to an active user, and
 *       queues the email in the email outbox in the same transaction (Step 6.2), so neither the answer nor its timing
 *       reveals whether an account exists; the outbox job sends it with no transaction open (CLAUDE.md hard rule 2).</li>
 *   <li>{@link #confirm} accepts a token once, within its expiry, sets the new password and ends every session of the
 *       user (sessionVersion + 1, all refresh tokens revoked).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final com.leads.mail.EmailOutbox outbox;
    private final RequestRateLimiter rateLimiter;
    private final CompanyClock clock;
    private final PlatformTransactionManager transactions;

    @Value("${app.public-url:http://localhost:4200}")
    private String publicUrl = "http://localhost:4200";

    @Value("${app.security.password-reset.expiry-minutes:30}")
    private int expiryMinutes = 30;

    public static final int MIN_PASSWORD = 8;
    public static final int MAX_PASSWORD = 128;

    public void request(String rawEmail, String clientIp) {
        String emailAddress = rawEmail == null ? "" : rawEmail.trim();
        rateLimiter.check("pwreset-ip:" + clientIp, 10, 900_000);
        rateLimiter.check("pwreset-user:" + emailAddress.toLowerCase(Locale.ROOT), 3, 900_000);
        new TransactionTemplate(transactions).executeWithoutResult(st -> {
            User user = users.findByEmail(emailAddress).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())
                    || (user.getOrganization() != null && !Boolean.TRUE.equals(user.getOrganization().getIsActive()))) return; // same answer, no email
            LocalDateTime now = clock.serverNow();
            // A new request supersedes any earlier unused link.
            jdbc.update("UPDATE password_resets SET used_at = ? WHERE user_id = ? AND used_at IS NULL", Timestamp.valueOf(now), user.getId());
            String raw = TokenHashing.newToken();
            jdbc.update("INSERT INTO password_resets (user_id, token_hash, expires_at, created_at, ip) VALUES (?, ?, ?, ?, ?)",
                    user.getId(), TokenHashing.sha256(raw), Timestamp.valueOf(now.plusMinutes(expiryMinutes)), Timestamp.valueOf(now),
                    clientIp == null ? null : clientIp.substring(0, Math.min(64, clientIp.length())));
            // Step 6.2: queued in this transaction (only if the token commits); the email-outbox job sends it later.
            String link = publicUrl.replaceAll("/+$", "") + "/reset-password?token=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
            outbox.enqueue(user.getEmail(), com.leads.mail.MailTemplate.PASSWORD_RESET, java.util.Map.of("link", link, "minutes", expiryMinutes));
        });
    }

    public void confirm(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD || newPassword.length() > MAX_PASSWORD) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a password of " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters.");
        }
        Long userId = new TransactionTemplate(transactions).execute(st -> {
            if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) return null;
            List<Object[]> rows = jdbc.query("SELECT id, user_id, expires_at, used_at FROM password_resets WHERE token_hash = ? FOR UPDATE",
                    (rs, i) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toLocalDateTime(), rs.getTimestamp(4)},
                    TokenHashing.sha256(rawToken));
            if (rows.isEmpty()) return null;
            Object[] row = rows.get(0);
            LocalDateTime now = clock.serverNow();
            if (row[3] != null || ((LocalDateTime) row[2]).isBefore(now)) return null;
            User user = users.findForAuthenticationById((Long) row[1]).orElse(null); // id from the verified reset token
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())) return null;
            jdbc.update("UPDATE password_resets SET used_at = ? WHERE id = ?", Timestamp.valueOf(now), row[0]);
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setSessionVersion((user.getSessionVersion() == null ? 0L : user.getSessionVersion()) + 1); // ends access tokens
            users.save(user);
            refreshTokens.revokeAllForUser(user.getId());                                                   // ends refresh tokens
            return user.getId();
        });
        if (userId == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "This reset link is invalid or has expired. Request a new one.");
        log.info("Password reset completed for user {}", userId);
    }
}
