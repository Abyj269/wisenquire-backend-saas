package com.leads.it.matrix;

import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.Quotation;

import java.util.List;
import java.util.Map;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 08 P1: the 7 QuotationController endpoints. A quotation has no branch of its own: its
 * scope is its lead's branch, so the record endpoints are BRANCH scope. Writes use fresh quotations
 * on fresh leads, with probes. OWN rows are in OwnScopeQuotationsIT (own_scope.enforced stays OFF here).
 */
final class QuotationRows {

    private static final List<Map<String, Object>> ITEMS = List.of(Map.of("itemName", "IT item", "quantity", 1, "unitPrice", 10));

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("POST /quotations", BRANCH, (f, s, p) ->
                        withJson(post("/quotations"), json("leadId", f.lead(s), "items", ITEMS))),
                EndpointSpec.of("PUT /quotations/{id}", BRANCH, (f, s, p) -> {
                    Long lead = f.freshLead(s);
                    Long id = watched(f, p, f.freshQuotation(s, lead, null));
                    return withJson(put("/quotations/{id}", id), json("leadId", lead, "items", ITEMS, "notes", "IT matrix"));
                }),
                EndpointSpec.of("GET /quotations/{id}", BRANCH, (f, s, p) -> get("/quotations/{id}", f.freshQuotation(s, f.freshLead(s), null))),
                EndpointSpec.of("GET /quotations", COMPANY, (f, s, p) -> get("/quotations").param("companyId", str(f.company(s)))),
                EndpointSpec.of("DELETE /quotations/{id}", BRANCH, (f, s, p) -> {
                    Long id = f.freshQuotation(s, f.freshLead(s), null);
                    p.watch(() -> String.valueOf(f.exists(Quotation.class, id)));
                    return delete("/quotations/{id}", id);
                }),
                EndpointSpec.of("GET /quotations/{id}/pdf", BRANCH, (f, s, p) -> get("/quotations/{id}/pdf", f.freshQuotation(s, f.freshLead(s), null))),
                EndpointSpec.of("POST /quotations/{id}/send-whatsapp", BRANCH, (f, s, p) -> {
                    Long id = watched(f, p, f.freshQuotation(s, f.freshLead(s), null));
                    return withJson(post("/quotations/{id}/send-whatsapp", id), "{}");
                }).noAllowedRow("needs a linked WhatsApp conversation and a Meta configuration; the scope refusals are the rows that matter, and OwnScopeQuotationsIT covers OWN"));
    }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Quotation.class, id, q -> q.getStatus() + "|" + q.getNotes() + "|" + q.getGrandTotal() + "|" + q.getUpdatedAt()));
        return id;
    }

    private QuotationRows() { }
}
