package com.leads.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Module 14 P1 (D15, R20): the startup timezone guard. */
class ServerZoneGuardTest {

    @Test
    void aUtcServerFailsFastInFailMode() {
        var e = assertThrows(IllegalStateException.class, () -> ServerZoneGuard.check(ZoneId.of("UTC"), ServerZoneGuard.Mode.FAIL));
        assertTrue(e.getMessage().contains("Asia/Dubai"), e.getMessage());
    }

    @Test
    void warnAndOffDoNotStopStartup() {
        assertFalse(ServerZoneGuard.check(ZoneId.of("UTC"), ServerZoneGuard.Mode.WARN));
        assertFalse(ServerZoneGuard.check(ZoneId.of("Asia/Kolkata"), ServerZoneGuard.Mode.OFF));
    }

    @Test
    void dubaiPasses() {
        assertTrue(ServerZoneGuard.check(ZoneId.of("Asia/Dubai"), ServerZoneGuard.Mode.FAIL));
    }

    @Test
    void theModeIsReadCaseInsensitively() {
        assertDoesNotThrow(() -> new ServerZoneGuard(" Fail "));
        assertThrows(IllegalArgumentException.class, () -> new ServerZoneGuard("maybe"));
    }
}
