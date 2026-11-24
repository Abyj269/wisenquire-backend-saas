package com.leads.flags;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D13 hygiene: keys unique and well-formed, every flag has an owner and an expiry. Expired flags are
 * a CI WARNING (printed as a GitHub annotation), not a failure: remove a flag once it has been at
 * 100% for 2 weeks, or extend its expiry with a reason.
 */
class FeatureFlagHygieneTest {

    @Test
    void everyFlagHasAUniqueKeyAnOwnerAndAnExpiry() {
        Set<String> keys = new HashSet<>();
        for (FeatureFlag f : FeatureFlag.values()) {
            assertTrue(keys.add(f.key), "duplicate key " + f.key);
            assertTrue(f.key.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"), "key format: " + f.key);
            assertFalse(f.owner.isBlank(), f.key + " has no owner");
            assertNotNull(f.expiresAt, f.key + " has no expiry");
        }
    }

    @Test
    void warnAboutExpiredFlags() {
        LocalDate today = LocalDate.now();
        Arrays.stream(FeatureFlag.values()).filter(f -> f.expiresAt.isBefore(today)).forEach(f ->
                System.out.println("::warning::Feature flag " + f.key + " (owner " + f.owner + ") expired on " + f.expiresAt));
    }
}
