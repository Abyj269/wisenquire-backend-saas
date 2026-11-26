package com.leads.config;

import com.leads.service.CompanyClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Module 14 P1 (D15, R20): every stored LocalDateTime is server wall-clock in
 * {@link CompanyClock#SERVER_ZONE}. A JVM in another zone (for example a container that
 * defaults to UTC) would silently shift every {@code LocalDateTime.now()}, so the app checks
 * the zone at startup. {@code app.server-zone-guard}: {@code fail} (prod) refuses to start,
 * {@code warn} (default) logs an error, {@code off} skips the check.
 */
@Slf4j
@Component
public class ServerZoneGuard {

    public enum Mode { FAIL, WARN, OFF }

    private final Mode mode;

    public ServerZoneGuard(@Value("${app.server-zone-guard:warn}") String mode) {
        this.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    @EventListener(ApplicationStartedEvent.class)
    public void onStarted() { check(ZoneId.systemDefault(), mode); }

    /** Throws in FAIL mode when {@code jvmZone} is not the server zone; returns whether the zone is correct. */
    static boolean check(ZoneId jvmZone, Mode mode) {
        boolean ok = CompanyClock.SERVER_ZONE.equals(jvmZone);
        if (mode == Mode.OFF) return ok;
        if (ok) {
            log.info("Server timezone: {} (D15 server zone, guard {})", jvmZone, mode);
            return true;
        }
        String message = "Server timezone is " + jvmZone + " but must be " + CompanyClock.SERVER_ZONE
                + " (D15/R20): set TZ=Asia/Dubai and -Duser.timezone=Asia/Dubai. Override with APP_SERVER_ZONE_GUARD=warn only as a stop-gap.";
        if (mode == Mode.FAIL) throw new IllegalStateException(message);
        log.error(message);
        return false;
    }
}
