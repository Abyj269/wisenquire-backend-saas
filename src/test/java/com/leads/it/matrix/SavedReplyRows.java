package com.leads.it.matrix;

import com.leads.model.entity.SavedReply;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 15 P1: the 4 SavedReplyController endpoints. Reads are COMPANY (every agent uses saved replies);
 * writes are COMPANY_WIDE (F-12, flag lead_settings.company_wide_writes ON in the matrix), so branch-level
 * personas get X_COMPANY_WIDE rows although they hold LEAD_SETTINGS. Every fixture persona holds
 * LEAD_SETTINGS, so there is no CFG_SCOPE persona for these endpoints.
 */
final class SavedReplyRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /settings/saved-replies", COMPANY,
                        (f, s, p) -> get("/settings/saved-replies").param("companyId", str(f.company(s)))),
                EndpointSpec.of("POST /settings/saved-replies", COMPANY_WIDE, (f, s, p) -> withJson(
                        post("/settings/saved-replies").param("companyId", str(f.company(s))), json("title", "IT reply", "body", "Hello", "active", true))),
                EndpointSpec.of("PUT /settings/saved-replies/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshSavedReply(s);
                    p.watch(() -> f.state(SavedReply.class, id, r -> r.getTitle() + "|" + r.isActive()));
                    return withJson(put("/settings/saved-replies/{id}", id).param("companyId", str(f.company(s))),
                            json("title", "IT renamed reply", "body", "Hi", "active", false));
                }),
                EndpointSpec.of("DELETE /settings/saved-replies/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshSavedReply(s);
                    p.watch(() -> String.valueOf(f.exists(SavedReply.class, id)));
                    return delete("/settings/saved-replies/{id}", id).param("companyId", str(f.company(s)));
                }));
    }

    private SavedReplyRows() { }
}
