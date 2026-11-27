package com.leads.tools;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Map;

/**
 * Flyway as a pre-deploy step (plan Phase 0, D10). It runs the same migrations the app runs at
 * start-up, then exits, without starting Spring, the web server, schedulers or any job. App
 * containers can then start with {@code SPRING_FLYWAY_ENABLED=false}.
 *
 * <p>Container usage (see {@code docker/README.md}):
 * <pre>
 *   java -cp app.jar -Dloader.main=com.leads.tools.MigrateMain \
 *        org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 * Environment: {@code DB_URL}, or {@code DB_HOST} / {@code DB_PORT} / {@code DB_NAME} (same JDBC
 * options as {@code application-prod.yml}), plus {@code DB_USERNAME} and {@code DB_PASSWORD}.
 *
 * <p>The live WAR on Tomcat is unaffected: it keeps running Flyway at start-up as today.
 */
public final class MigrateMain {

    static final String LOCATIONS = "classpath:db/migration";

    private MigrateMain() {}

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        String url = jdbcUrl(env);
        String user = require(env, "DB_USERNAME");
        // Must be set; may be empty (e.g. a local XAMPP root account). Prod always sets a real one.
        String password = env.get("DB_PASSWORD");
        if (password == null) throw new IllegalStateException("Missing required environment variable DB_PASSWORD");
        MigrateResult result = configure(url, user, password).load().migrate();
        System.out.printf("Flyway: %d migration(s) applied; schema now at version %s%n",
                result.migrationsExecuted, result.targetSchemaVersion);
        if (!result.success) {
            System.exit(1);
        }
    }

    /**
     * Same Flyway settings as the app profiles. Out-of-order is ON (00-INDEX, owner decision A, 2026-11-25): module
     * blocks are applied out of number order (V110 ran before V60), so without it this step refuses every lower
     * version added later (found in Step 6.3: V62-V64 were refused on staging).
     */
    static org.flywaydb.core.api.configuration.FluentConfiguration configure(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations(LOCATIONS)
                .outOfOrder(true);
    }

    /** Same options as application-prod.yml so the migrate step talks to the DB exactly like the app. */
    static String jdbcUrl(Map<String, String> env) {
        String explicit = env.get("DB_URL");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String host = require(env, "DB_HOST");
        String port = env.getOrDefault("DB_PORT", "3306");
        String name = env.getOrDefault("DB_NAME", "wisenquire_db_new");
        return "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?sslMode=REQUIRED&allowPublicKeyRetrieval=false&serverTimezone=UTC"
                + "&characterEncoding=UTF-8&useUnicode=true";
    }

    private static String require(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }
}
