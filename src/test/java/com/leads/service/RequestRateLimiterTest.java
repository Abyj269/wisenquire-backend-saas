package com.leads.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Module 02 P1, F-13: the limiter still limits, but a full store never blocks a fresh key. */
class RequestRateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MovableClock extends Clock {
        private volatile long millis = Instant.parse("2026-11-25T10:00:00Z").toEpochMilli();
        void advance(long ms) { millis += ms; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final MovableClock clock = new MovableClock();
    private final RequestRateLimiter limiter = new RequestRateLimiter(clock);

    @Test
    void with25000UniqueKeysAFreshValidLoginIsNeverBlocked() {
        // F-13: the old limiter refused every new key above 20,000 entries (a global login lock-out).
        for (int i = 0; i < 25_000; i++) {
            limiter.check("login-ip:10." + (i / 65536) + "." + ((i / 256) % 256) + "." + (i % 256), 30, 60_000);
            limiter.check("login-user:flood" + i + "@attacker.invalid", 10, 60_000);
        }
        assertDoesNotThrow(() -> limiter.check("login-ip:198.51.100.7", 30, 60_000), "fresh IP");
        assertDoesNotThrow(() -> limiter.check("login-user:real.user@customer.example", 10, 60_000), "fresh email");
        assertDoesNotThrow(() -> limiter.check("otp:fresh-challenge", 8, 300_000), "fresh OTP challenge");
        assertDoesNotThrow(() -> limiter.check("otp-issue:42", 1, 60_000), "fresh OTP issue");
    }

    @Test
    void floodingOneKeySpaceBeyondItsCapacityStillNeverBlocksAFreshKey() {
        int beyond = RequestRateLimiter.CAPACITY.get("login-user") + 5_000;
        for (int i = 0; i < beyond; i++) limiter.check("login-user:flood" + i + "@attacker.invalid", 10, 60_000);
        assertDoesNotThrow(() -> limiter.check("login-user:real.user@customer.example", 10, 60_000));
        assertTrue(limiter.size("login-user") <= RequestRateLimiter.CAPACITY.get("login-user"), "the store stays bounded");
        assertDoesNotThrow(() -> limiter.check("login-ip:198.51.100.7", 30, 60_000), "another key space is unaffected");
    }

    @Test
    void theLimitStillAppliesAndResetsAfterTheWindow() {
        for (int i = 0; i < 30; i++) limiter.check("login-ip:198.51.100.7", 30, 60_000);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> limiter.check("login-ip:198.51.100.7", 30, 60_000));
        assertEquals(429, ex.getStatusCode().value());
        clock.advance(59_000);
        assertThrows(ResponseStatusException.class, () -> limiter.check("login-ip:198.51.100.7", 30, 60_000), "still inside the window");
        clock.advance(1_001);
        assertDoesNotThrow(() -> limiter.check("login-ip:198.51.100.7", 30, 60_000), "a new window");
    }

    @Test
    void aRefusedCallDoesNotExtendOrReset() {
        limiter.check("otp-issue:7", 1, 60_000);
        for (int i = 0; i < 5; i++) assertThrows(ResponseStatusException.class, () -> limiter.check("otp-issue:7", 1, 60_000));
        clock.advance(60_000);
        assertDoesNotThrow(() -> limiter.check("otp-issue:7", 1, 60_000));
    }

    @Test
    void keySpacesAreIndependent() {
        for (int i = 0; i < 10; i++) limiter.check("login-user:a@x.test", 10, 60_000);
        assertThrows(ResponseStatusException.class, () -> limiter.check("login-user:a@x.test", 10, 60_000));
        assertDoesNotThrow(() -> limiter.check("login-ip:a@x.test", 30, 60_000), "same suffix, other space");
        assertEquals("login-user", RequestRateLimiter.space("login-user:a@x.test"));
        assertEquals("default", RequestRateLimiter.space("no-prefix"));
    }

    @Test
    void concurrentCallsNeverExceedTheMax() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    try { limiter.check("login-ip:203.0.113.9", 30, 60_000); allowed.incrementAndGet(); }
                    catch (ResponseStatusException refused) { /* expected beyond 30 */ }
                }
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(30, allowed.get());
    }
}
