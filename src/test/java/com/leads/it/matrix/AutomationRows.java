package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.Automation;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 17 P1: the 11 AutomationController endpoints. Branch automations are scoped by their branch; a
 * company-level automation is readable by branch users but only company-wide users may change it
 * (X_COMPANY_WIDE rows). WHATSAPP_AUTOMATIONS is manager-only in the fixture, so agents get CFG_SCOPE rows.
 * Automations here trigger only on a keyword no test sends, so a published one never fires elsewhere.
 */
final class AutomationRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};
    private static final String NEVER = "it-matrix-never-sent";

    static List<EndpointSpec> specs() {
        return List.of(
                spec("GET /automations", (f, s, p) -> get("/automations").param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),
                spec("GET /automations/{id}", (f, s, p) -> get("/automations/{id}", fresh(f, s))),
                companyWide(spec("POST /automations", (f, s, p) -> withJson(post("/automations"), body(f, s, true))),
                        (f, s, p) -> withJson(post("/automations"), body(f, s, false))),
                companyWide(spec("PUT /automations/{id}", (f, s, p) -> withJson(put("/automations/{id}", watched(f, p, fresh(f, s))), body(f, s, true))),
                        (f, s, p) -> withJson(put("/automations/{id}", watched(f, p, f.freshAutomation(s, true, NEVER))), body(f, s, false))),
                companyWide(spec("POST /automations/{id}/publish", (f, s, p) -> post("/automations/{id}/publish", watched(f, p, fresh(f, s)))),
                        (f, s, p) -> post("/automations/{id}/publish", watched(f, p, f.freshAutomation(s, true, NEVER)))),
                companyWide(spec("POST /automations/{id}/pause", (f, s, p) -> post("/automations/{id}/pause", watched(f, p, fresh(f, s)))),
                        (f, s, p) -> post("/automations/{id}/pause", watched(f, p, f.freshAutomation(s, true, NEVER)))),
                companyWide(spec("POST /automations/{id}/duplicate", (f, s, p) -> post("/automations/{id}/duplicate", fresh(f, s))),
                        (f, s, p) -> post("/automations/{id}/duplicate", f.freshAutomation(s, true, NEVER))),
                companyWide(spec("DELETE /automations/{id}", (f, s, p) -> delete("/automations/{id}", watched(f, p, fresh(f, s)))),
                        (f, s, p) -> delete("/automations/{id}", watched(f, p, f.freshAutomation(s, true, NEVER)))),
                spec("GET /automations/{id}/executions", (f, s, p) -> get("/automations/{id}/executions", fresh(f, s))),
                foreignExecution(spec("GET /automations/{id}/executions/{execId}", (f, s, p) -> {
                    Long automation = fresh(f, s);
                    return get("/automations/{id}/executions/{execId}", automation, f.freshAutomationExecution(automation));
                })),
                spec("GET /automations/{id}/analytics", (f, s, p) -> get("/automations/{id}/analytics", fresh(f, s))));
    }

    private static EndpointSpec spec(String key, EndpointSpec.Request request) {
        return EndpointSpec.of(key, BRANCH, request).onlyFor(MANAGERS).deniedFor(AGENTS);
    }

    /** A branch-level user may not create or change a company-level automation of its own company. */
    private static EndpointSpec companyWide(EndpointSpec spec, EndpointSpec.Request onCompanyLevel) {
        return spec.row(RowType.X_COMPANY_WIDE, 'A', Persona.BRANCH_MGR, Slot.A1a, Outcome.DENIED, onCompanyLevel);
    }

    /** An execution of another automation, asked for under the caller's own automation id. */
    private static EndpointSpec foreignExecution(EndpointSpec spec) {
        return spec.row(RowType.X_BRANCH, 'A', Persona.BRANCH_MGR, Slot.A1a, Outcome.DENIED, (f, s, p) -> {
            Long mine = fresh(f, Slot.A1a);
            Long otherBranchExecution = f.freshAutomationExecution(fresh(f, Slot.A1b));
            return get("/automations/{id}/executions/{execId}", mine, otherBranchExecution);
        });
    }

    private static String body(TenantFixture f, Slot s, boolean onBranch) {
        return onBranch
                ? json("companyId", f.company(s), "branchId", f.branch(s), "name", "IT matrix automation", "triggerType", "KEYWORD",
                        "triggerJson", "{\"keyword\":\"" + NEVER + "\",\"matchType\":\"EXACT_MATCH\"}",
                        "actionsJson", "[{\"type\":\"SEND_MESSAGE\",\"messageText\":\"IT\"}]")
                : json("companyId", f.company(s), "name", "IT matrix company automation", "triggerType", "KEYWORD",
                        "triggerJson", "{\"keyword\":\"" + NEVER + "\",\"matchType\":\"EXACT_MATCH\"}",
                        "actionsJson", "[{\"type\":\"SEND_MESSAGE\",\"messageText\":\"IT\"}]");
    }

    private static Long fresh(TenantFixture f, Slot s) { return f.freshAutomation(s, false, NEVER); }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Automation.class, id, a -> a.getStatus() + "|" + a.getName() + "|" + a.getDeletedAt()));
        return id;
    }

    private AutomationRows() { }
}
