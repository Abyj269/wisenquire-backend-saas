package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.WhatsAppConfiguration;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 11 P1: the 5 WhatsAppConfigController endpoints. Save and delete use fresh connections
 * (a unique phone-number id each); "test connection" calls the mocked Meta client only. Agents have no
 * COMPANY_BRANCH_SETTINGS rights in the fixture, so the admin endpoints get CFG_SCOPE rows for them.
 * The list stays open to WHATSAPP_LIVE_CHAT:VIEW (the inbox number filter), so agents pass it.
 */
final class WhatsAppConfigRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /whatsapp/configs", COMPANY, (f, s, p) -> get("/whatsapp/configs").param("companyId", str(f.company(s)))),
                EndpointSpec.of("GET /whatsapp/configs/branch/{branchId}", BRANCH, (f, s, p) -> get("/whatsapp/configs/branch/{branchId}", f.branch(s)))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /whatsapp/configs", BRANCH, (f, s, p) -> withJson(post("/whatsapp/configs"), json(
                        "branchId", f.branch(s), "phoneNumberId", "it-new-pnid-" + f.phone(), "wabaId", "it-new-waba",
                        "accessToken", "it-not-a-real-token", "appSecret", "it-not-a-real-secret", "verifyToken", "it-verify-new")))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("DELETE /whatsapp/configs/{id}", BRANCH, (f, s, p) -> {
                    Long id = fresh(f, s);
                    p.watch(() -> String.valueOf(f.exists(WhatsAppConfiguration.class, id)));
                    return delete("/whatsapp/configs/{id}", id);
                }).onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /whatsapp/configs/{id}/test", BRANCH, (f, s, p) -> post("/whatsapp/configs/{id}/test", f.config(s)))
                        .onlyFor(MANAGERS).deniedFor(AGENTS));
    }

    private static Long fresh(TenantFixture f, Slot s) { return f.freshWhatsAppConfig(s); }

    private WhatsAppConfigRows() { }
}
