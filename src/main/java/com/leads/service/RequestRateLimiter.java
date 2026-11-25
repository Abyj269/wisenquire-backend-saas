package com.leads.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, per-instance abuse protection (edge limits should also apply across replicas; S12 makes
 * it distributed). {@code check(key, max, window)}: at most {@code max} calls per key per window.
 * <p>
 * F-13 fix (module 02 P1). The old version refused EVERY new key once its single map held 20,000
 * entries, so filling it (e.g. many login attempts with random emails) locked everyone out. Now:
 * <ul>
 *   <li>one bounded store per key space (the prefix before ':': {@code login-ip}, {@code login-user},
 *       {@code otp}, {@code otp-issue}, ...), so one space cannot crowd out another;</li>
 *   <li>each entry expires with its own window;</li>
 *   <li>a full store evicts the least valuable entries (Caffeine W-TinyLFU). A key is NEVER refused
 *       because the store is full: under a flood, limiting "fails open" for evicted keys instead of
 *       blocking real users;</li>
 *   <li>{@code login-ip} gets the largest store, so per-IP limits are the last to be forgotten.</li>
 * </ul>
 * Time comes from the application {@link Clock} (tests move it).
 */
@Service
public class RequestRateLimiter {

    /** Store size per key space; anything not listed gets {@link #DEFAULT_CAPACITY}. */
    static final Map<String, Integer> CAPACITY = Map.of(
            "login-ip", 200_000,
            "login-user", 100_000,
            "otp", 100_000,
            "otp-issue", 100_000);
    static final int DEFAULT_CAPACITY = 50_000;

    private record Window(long start, int count, long durationMs) { }

    private final Clock clock;
    private final Map<String, Cache<String, Window>> spaces = new ConcurrentHashMap<>();

    @Autowired
    public RequestRateLimiter(Clock clock) { this.clock = clock; }

    /** Plain construction (unit tests, scripts): the system clock. */
    public RequestRateLimiter() { this(Clock.systemUTC()); }

    public void check(String key, int max, long durationMs) {
        long now = clock.millis();
        Cache<String, Window> store = spaces.computeIfAbsent(space(key), this::newStore);
        store.asMap().compute(key, (k, w) -> {
            if (w == null || now - w.start() >= w.durationMs()) return new Window(now, 1, durationMs);
            if (w.count() >= max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please retry later.");
            return new Window(w.start(), w.count() + 1, durationMs);
        });
    }

    /** Entries currently held for a key space (tests and diagnostics). */
    long size(String space) {
        Cache<String, Window> store = spaces.get(space);
        if (store == null) return 0;
        store.cleanUp();
        return store.estimatedSize();
    }

    static String space(String key) {
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(0, colon) : "default";
    }

    private Cache<String, Window> newStore(String space) {
        return Caffeine.newBuilder()
                .maximumSize(CAPACITY.getOrDefault(space, DEFAULT_CAPACITY))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .expireAfter(new Expiry<String, Window>() {
                    @Override public long expireAfterCreate(String k, Window w, long t) { return remaining(w); }
                    @Override public long expireAfterUpdate(String k, Window w, long t, long current) { return remaining(w); }
                    @Override public long expireAfterRead(String k, Window w, long t, long current) { return current; }
                })
                .build();
    }

    /** Time left in the window, in nanoseconds (an entry is useless once its window has passed). */
    private long remaining(Window w) {
        long left = w.start() + w.durationMs() - clock.millis();
        return TimeUnit.MILLISECONDS.toNanos(Math.max(1, left));
    }
}
