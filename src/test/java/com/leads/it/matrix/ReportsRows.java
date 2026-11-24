package com.leads.it.matrix;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Module 10 Reports: all 6 ReportsController endpoints. /reports/branches compares every
 * branch of the company, so it needs company-wide scope (branch-level personas denied).
 * The OWN-AGG rows (agents see only their own figures) are parked until D14.
 */
final class ReportsRows {

    static List<EndpointSpec> specs() {
        return List.of(
                report("/reports/customers"),
                report("/reports/staff-performance"),
                report("/reports/lead-sources"),
                EndpointSpec.of("GET /reports/branches", COMPANY_WIDE, (f, s, p) -> get("/reports/branches")
                        .param("companyId", str(f.company(s))).param("fromDate", "2026-01-01").param("toDate", "2026-12-31")),
                report("/reports/response-time"),
                report("/reports/revenue-forecast"));
    }

    private static EndpointSpec report(String path) {
        return EndpointSpec.of("GET " + path, BRANCH, (f, s, p) -> get(path)
                .param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))
                .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"));
    }

    private ReportsRows() { }
}
