package com.leads.service;

import com.leads.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Module 02 P2 (D11, flag {@code auth.refresh_tokens}): the store of rotating refresh tokens (V60). Only a SHA-256
 * hash of each token is kept. Package-private: only AuthService and PasswordResetService use it, with ids taken
 * from a verified token. Every method runs in the caller's transaction; {@link #lock} takes a row lock so two
 * refreshes of the same token cannot both rotate it. No external call is made here.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JdbcTemplate jdbc;
    private final CompanyClock clock;

    @Value("${app.security.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs = 604_800_000L;

    /** One stored token. {@code replacedBy} is set once the token has been rotated. */
    public record Row(long id, long userId, String familyId, long sessionVersion, LocalDateTime expiresAt,
                      LocalDateTime revokedAt, Long replacedBy) { }

    /** A newly issued token: the raw value goes to the client, the id links the rotation. */
    public record Issued(long id, String token) { }

    long refreshExpirationMs() { return refreshExpirationMs; }

    String newFamily() { return UUID.randomUUID().toString(); }

    Issued issue(User user, String familyId, String ip, String userAgent) {
        String raw = TokenHashing.newToken();
        LocalDateTime now = clock.serverNow();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("INSERT INTO refresh_tokens (user_id, family_id, token_hash, session_version, expires_at, created_at, ip, user_agent) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, user.getId());
            ps.setString(2, familyId);
            ps.setString(3, TokenHashing.sha256(raw));
            ps.setLong(4, user.getSessionVersion() == null ? 0L : user.getSessionVersion());
            ps.setTimestamp(5, Timestamp.valueOf(now.plusNanos(refreshExpirationMs * 1_000_000L)));
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setString(7, truncate(ip, 64));
            ps.setString(8, truncate(userAgent, 255));
            return ps;
        }, key);
        return new Issued(key.getKey().longValue(), raw);
    }

    /** The stored token for a raw value, row-locked for the rest of the caller's transaction. */
    Optional<Row> lock(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 200) return Optional.empty();
        List<Row> rows = jdbc.query("SELECT id, user_id, family_id, session_version, expires_at, revoked_at, replaced_by FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toLocalDateTime(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime(),
                        rs.getObject(7) == null ? null : rs.getLong(7)),
                TokenHashing.sha256(raw));
        return rows.stream().findFirst();
    }

    /** The token was rotated: it points at its successor and can no longer be used (except within the reuse grace). */
    void replace(long id, long successorId) {
        jdbc.update("UPDATE refresh_tokens SET replaced_by = ?, revoked_at = COALESCE(revoked_at, ?) WHERE id = ?",
                successorId, Timestamp.valueOf(clock.serverNow()), id);
    }

    /** Ends one login (logout, reuse detected, session revoked). */
    int revokeFamily(String familyId) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                Timestamp.valueOf(clock.serverNow()), familyId);
    }

    /** Ends every login of a user (password reset). */
    int revokeAllForUser(long userId) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.valueOf(clock.serverNow()), userId);
    }

    LocalDateTime now() { return clock.serverNow(); }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
