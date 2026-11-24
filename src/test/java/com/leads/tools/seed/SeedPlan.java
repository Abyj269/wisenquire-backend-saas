package com.leads.tools.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the synthetic tenants (01-foundation Part A item 5).
 * <pre>
 *   t01        large   2 companies x 5 branches   50,000 leads  200,000 messages   Asia/Kolkata
 *   t02..t05   medium  2 companies x 2 branches    2,000 leads    8,000 messages
 *   t06..t20   small   1 company   x 2 branches      100 leads      400 messages
 * </pre>
 * Companies cycle through the 7 allowed timezones (D15 option A, extended). Scale {@code ci}
 * keeps the same structure with tiny volumes, for the CI drift check.
 */
record SeedPlan(List<Tenant> tenants) {

    /** The 7 Year-1 zones (CLAUDE.md). */
    static final String[] ZONES = {"Asia/Kolkata", "Asia/Dubai", "Asia/Muscat", "Asia/Riyadh", "Asia/Qatar", "Asia/Bahrain", "Asia/Kuwait"};

    record Company(String code, String zone, int branches) { }

    record Tenant(String code, String size, List<Company> companies, int leads, int customers, int conversations, int messages) {
        int branchCount() { return companies.stream().mapToInt(Company::branches).sum(); }
    }

    static SeedPlan forScale(String scale) {
        boolean ci = "ci".equals(scale);
        if (!ci && !"full".equals(scale)) throw new IllegalArgumentException("scale must be full or ci, got " + scale);
        List<Tenant> out = new ArrayList<>();
        int zone = 1; // t01 takes Kolkata explicitly; the rest cycle from Dubai
        out.add(tenant("t01", "large", List.of(new Company("c1", "Asia/Kolkata", 5), new Company("c2", "Asia/Kolkata", 5)),
                ci ? 60 : 50_000, ci ? 45 : 35_000, ci ? 20 : 20_000, ci ? 80 : 200_000));
        for (int t = 2; t <= 5; t++) {
            String z1 = ZONES[zone++ % ZONES.length], z2 = ZONES[zone++ % ZONES.length];
            out.add(tenant(code(t), "medium", List.of(new Company("c1", z1, 2), new Company("c2", z2, 2)),
                    ci ? 20 : 2_000, ci ? 15 : 1_500, ci ? 8 : 800, ci ? 32 : 8_000));
        }
        int smallTenants = ci ? 3 : 15;
        for (int t = 6; t < 6 + smallTenants; t++) {
            out.add(tenant(code(t), "small", List.of(new Company("c1", ZONES[zone++ % ZONES.length], 2)),
                    ci ? 10 : 100, ci ? 8 : 80, ci ? 4 : 40, ci ? 16 : 400));
        }
        return new SeedPlan(out);
    }

    private static Tenant tenant(String code, String size, List<Company> companies, int leads, int customers, int conversations, int messages) {
        return new Tenant(code, size, companies, leads, customers, conversations, messages);
    }

    private static String code(int t) { return String.format("t%02d", t); }
}
