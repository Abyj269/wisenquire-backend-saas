package com.leads.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plan Phase 0 (Step 3.1): the Flyway pre-deploy step builds the same JDBC URL as application-prod.yml. */
class MigrateMainTest {

    @Test void buildsProdStyleUrlFromParts() {
        String url = MigrateMain.jdbcUrl(Map.of("DB_HOST", "db.internal", "DB_PORT", "3307", "DB_NAME", "wisenquire"));
        assertEquals("jdbc:mysql://db.internal:3307/wisenquire?sslMode=REQUIRED&allowPublicKeyRetrieval=false"
                + "&serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true", url);
    }

    @Test void defaultsMatchApplicationProdYml() {
        String url = MigrateMain.jdbcUrl(Map.of("DB_HOST", "h"));
        assertTrue(url.startsWith("jdbc:mysql://h:3306/wisenquire_db_new?sslMode=REQUIRED"));
    }

    @Test void explicitUrlWins() {
        assertEquals("jdbc:mysql://x/y", MigrateMain.jdbcUrl(Map.of("DB_URL", "jdbc:mysql://x/y", "DB_HOST", "ignored")));
    }

    @Test void missingHostFailsClearly() {
        var ex = assertThrows(IllegalStateException.class, () -> MigrateMain.jdbcUrl(Map.of()));
        assertTrue(ex.getMessage().contains("DB_HOST"));
    }

    @Test void unsetPasswordIsRejectedBeforeConnecting() {
        // DB_PASSWORD is absent from the test JVM's environment unless CI sets it; main() must fail fast, not connect.
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DB_PASSWORD") == null && System.getenv("DB_URL") == null
                && System.getenv("DB_HOST") == null);
        assertThrows(IllegalStateException.class, () -> MigrateMain.main(new String[0]));
    }

    @Test void migrationLocationIsTheAppsOwn() {
        assertEquals("classpath:db/migration", MigrateMain.LOCATIONS);
    }
}
