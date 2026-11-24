package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Broadcast;

import java.util.List;

import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 16 Broadcast: all 6 endpoints. These are the Phase -1 (F-1) regression rows:
 * an org-A persona must be denied on every endpoint with org-B IDs, and a branch-level
 * persona must be denied on a company-wide broadcast of its own company.
 */
final class BroadcastRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("POST /broadcasts", BRANCH, (f, s, p) -> withJson(post("/broadcasts"), json(
                        "companyId", f.company(s), "branchId", f.branch(s), "whatsappConfigurationId", f.config(s),
                        "name", "IT matrix broadcast", "templateName", "hello_world",
                        "audienceType", "CUSTOM_NUMBERS", "customNumbers", List.of(f.phone())))),

                companyWideRows(EndpointSpec.of("GET /broadcasts/{id}", BRANCH,
                        (f, s, p) -> get("/broadcasts/{id}", f.broadcast(s))), false),

                EndpointSpec.of("GET /broadcasts", BRANCH,
                        (f, s, p) -> get("/broadcasts").param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),

                // FINDING (Step 3.2): BroadcastServiceImpl.sendBroadcast calls
                // access.permission("BROADCASTS:SEND"), but no RolePermission column grants
                // ":SEND" (CustomUserDetailsService), so only a super admin or the scheduler can
                // send. ORG_ADMIN passes @PreAuthorize (ROLE_ORGANIZATION) and is then denied by
                // that permission check, so the X-ORG row below is denied before the F-1 record
                // check runs: it is not yet a discriminating row. Fix belongs to module 16.
                EndpointSpec.of("POST /broadcasts/{id}/send", BRANCH,
                        (f, s, p) -> post("/broadcasts/{id}/send", watched(f, p, f.freshBroadcast(s))))
                        .onlyFor(Persona.ORG_ADMIN)
                        .noAllowedRow("BROADCASTS:SEND cannot be granted to any tenant role (finding, module 16)"),

                companyWideRows(EndpointSpec.of("GET /broadcasts/{id}/logs", BRANCH,
                        (f, s, p) -> get("/broadcasts/{id}/logs", f.broadcast(s))), false),

                companyWideRows(EndpointSpec.of("DELETE /broadcasts/{id}", BRANCH,
                        (f, s, p) -> delete("/broadcasts/{id}", watched(f, p, f.freshBroadcast(s)))), true));
    }

    /** F-1: a company-wide broadcast (no branch) needs company-wide scope. */
    private static EndpointSpec companyWideRows(EndpointSpec spec, boolean fresh) {
        for (Persona p : new Persona[]{Persona.BRANCH_MGR, Persona.AGENT_OWN_1}) {
            spec.row(RowType.X_COMPANY_WIDE, 'A', p, Slot.A1a, Outcome.DENIED, (f, s, probe) -> {
                Long id = fresh ? watched(f, probe, f.freshCompanyWideBroadcast(s)) : f.companyWideBroadcast(s);
                String path = spec.key.substring(spec.key.indexOf(' ') + 1);
                return spec.key.startsWith("DELETE") ? delete(path, id) : get(path, id);
            });
        }
        return spec;
    }

    private static Long watched(com.leads.it.fixture.TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Broadcast.class, id, b -> b.getStatus() + "|" + b.getDeletedAt()));
        return id;
    }

    static String str(Long v) { return String.valueOf(v); }

    private BroadcastRows() { }
}
