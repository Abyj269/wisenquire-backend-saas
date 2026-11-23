package com.leads.config;

import com.leads.util.WhatsAppCredentialEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keyset batches keep legacy plaintext migration bounded; reruns are idempotent. */
@Component
@RequiredArgsConstructor
public class WhatsAppCredentialMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var converter = new WhatsAppCredentialEncryptor();
        long after = 0;
        while (true) {
            var rows = jdbc.queryForList("SELECT id, access_token, app_secret FROM whatsapp_configurations WHERE id > ? ORDER BY id LIMIT 100", after);
            if (rows.isEmpty()) return;
            for (var row : rows) {
                after = ((Number) row.get("id")).longValue();
                for (String column : new String[]{"access_token", "app_secret"}) {
                    String old = (String) row.get(column);
                    if (old != null && !old.startsWith(WhatsAppCredentialEncryptor.PREFIX)) {
                        jdbc.update("UPDATE whatsapp_configurations SET " + column + "=? WHERE id=? AND " + column + "=?",
                                converter.convertToDatabaseColumn(old), after, old);
                    }
                }
            }
        }
    }
}
