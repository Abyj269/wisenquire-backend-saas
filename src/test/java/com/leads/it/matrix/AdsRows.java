package com.leads.it.matrix;

import com.leads.dto.ads.AdConnectionTestResult;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.AdAccountConnection;
import com.leads.service.AdsProviderClient;
import com.leads.service.AdsProviderClientFactory;
import org.mockito.Mockito;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 19 P1: the 8 ads endpoints. Branch connections are scoped by their branch; a company-wide connection
 * (no branch) needs company-wide scope (X_COMPANY_WIDE rows for branch managers). Connection management needs
 * COMPANY_BRANCH_SETTINGS, which the fixture agents lack (CFG_SCOPE); the ad report needs REPORTS_ANALYTICS.
 * The Meta / Google clients are mocked ({@link #stub}); no real ad platform is called.
 */
final class AdsRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};

    static List<EndpointSpec> specs() {
        return List.of(
                admin(EndpointSpec.of("GET /ads/connections", COMPANY_WIDE,
                        (f, s, p) -> get("/ads/connections").param("companyId", str(f.company(s))))),
                admin(EndpointSpec.of("GET /ads/connections/branch/{branchId}", BRANCH,
                        (f, s, p) -> get("/ads/connections/branch/{id}", f.branch(s)))),
                admin(EndpointSpec.of("POST /ads/connections", BRANCH, (f, s, p) -> {
                    Long id = watched(f, p, f.freshAdConnection(s, false));
                    return withJson(post("/ads/connections"), json("id", id, "companyId", f.company(s), "branchId", f.branch(s),
                            "provider", "META", "externalAccountId", "act_it_renamed", "displayName", "IT renamed"));
                })),
                companyWide(admin(EndpointSpec.of("DELETE /ads/connections/{id}", BRANCH,
                        (f, s, p) -> delete("/ads/connections/{id}", watched(f, p, f.freshAdConnection(s, false))))), "DELETE"),
                companyWide(admin(EndpointSpec.of("POST /ads/connections/{id}/test", BRANCH,
                        (f, s, p) -> post("/ads/connections/{id}/test", f.freshAdConnection(s, false)))), "test"),
                companyWide(admin(EndpointSpec.of("POST /ads/connections/{id}/sync", BRANCH,
                        (f, s, p) -> post("/ads/connections/{id}/sync", watched(f, p, f.freshAdConnection(s, false))))), "sync"),
                companyWide(admin(EndpointSpec.of("POST /ads/connections/{id}/backfill", BRANCH,
                        (f, s, p) -> post("/ads/connections/{id}/backfill", f.freshAdConnection(s, false)))), "backfill"),
                EndpointSpec.of("GET /reports/ads/performance", BRANCH, (f, s, p) -> get("/reports/ads/performance")
                        .param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))));
    }

    private static EndpointSpec admin(EndpointSpec spec) { return spec.onlyFor(MANAGERS).deniedFor(AGENTS); }

    /** A branch manager may not act on its own company's company-wide connection. */
    private static EndpointSpec companyWide(EndpointSpec spec, String action) {
        return spec.row(RowType.X_COMPANY_WIDE, 'A', Persona.BRANCH_MGR, Slot.A1a, Outcome.DENIED, (f, s, p) -> {
            Long id = watched(f, p, f.freshAdConnection(s, true));
            return switch (action) {
                case "DELETE" -> delete("/ads/connections/{id}", id);
                default -> post("/ads/connections/{id}/" + action, id);
            };
        });
    }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(AdAccountConnection.class, id, c -> c.getExternalAccountId() + "|" + c.getLastSyncStatus()));
        return id;
    }

    /** Mocked Meta / Google clients for the allowed rows (the factory @MockBean is reset after every row). */
    static void stub(AdsProviderClientFactory factory) {
        AdsProviderClient client = Mockito.mock(AdsProviderClient.class);
        when(client.fetchAds(any())).thenReturn(List.of());
        when(client.fetchInsights(any(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(client.testConnection(any())).thenReturn(AdConnectionTestResult.builder().success(true).message("IT").build());
        when(factory.resolve(any())).thenReturn(client);
    }

    private AdsRows() { }
}
