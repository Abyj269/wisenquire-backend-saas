package com.leads.service;

import java.util.Locale;
import java.util.function.Predicate;

/** Module 03 P1 (V65): organization slugs, the platform-unique, URL-safe name of a tenant. */
public final class OrganizationSlugs {

    static final int MAX = 80;

    private OrganizationSlugs() { }

    /** Lower case; runs of anything other than a-z / 0-9 become '-'; trimmed; never empty. Same rule as V65. */
    public static String slugify(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) s = "org";
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-+$", "") : s;
    }

    /** The slug for {@code value}, with "-2", "-3", ... added until {@code taken} says it is free. */
    public static String unique(String value, Predicate<String> taken) {
        String base = slugify(value);
        if (!taken.test(base)) return base;
        for (int n = 2; n < 10_000; n++) {
            String candidate = base + "-" + n;
            if (!taken.test(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not find a free organization slug for " + base);
    }
}
