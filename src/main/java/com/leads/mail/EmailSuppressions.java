package com.leads.mail;

import com.leads.service.CompanyClock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Locale;

/** Module 02 P2 item 4: addresses the outbox never sends to (V63): hard bounces and complaints. */
@Component
@RequiredArgsConstructor
public class EmailSuppressions {

    public enum Reason { HARD_BOUNCE, COMPLAINT }

    private final JdbcTemplate jdbc;
    private final CompanyClock clock;

    static String normalise(String address) { return address == null ? "" : address.trim().toLowerCase(Locale.ROOT); }

    public boolean isSuppressed(String address) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM email_suppressions WHERE address = ?", Integer.class, normalise(address));
        return n != null && n > 0;
    }

    public void suppress(String address, Reason reason, String source) {
        jdbc.update("INSERT INTO email_suppressions (address, reason, source, created_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE reason = VALUES(reason), source = VALUES(source)",
                normalise(address), reason.name(), source == null ? null : source.substring(0, Math.min(60, source.length())),
                Timestamp.valueOf(clock.serverNow()));
    }
}
