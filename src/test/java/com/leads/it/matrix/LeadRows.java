package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.Lead;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Module 06 Leads: all 10 endpoints. */
final class LeadRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("POST /leads", BRANCH, (f, s, p) -> withJson(post("/leads"), json(
                        "companyId", f.company(s), "branchId", f.branch(s), "firstName", "IT Matrix",
                        "phone", f.phone(), "sourceId", f.source(s)))),

                EndpointSpec.of("PUT /leads/{id}", BRANCH, (f, s, p) -> {
                    Long id = watched(f, p, f.freshLead(s));
                    // Same phone as the lead's customer: a lead may not change the customer's identity.
                    String phone = f.state(Lead.class, id, Lead::getPhone);
                    return withJson(put("/leads/{id}", id), json("branchId", f.branch(s), "firstName", "IT Renamed",
                            "phone", phone, "sourceId", f.source(s), "statusId", f.status(s)));
                }),

                EndpointSpec.of("GET /leads/{id}", BRANCH, (f, s, p) -> get("/leads/{id}", f.lead(s))),

                EndpointSpec.of("GET /leads", BRANCH,
                        (f, s, p) -> get("/leads").param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),

                // Assigns a lead of the target slot to that slot's own agent: the attack is
                // "reassign someone else's lead", not "assign to a stranger".
                EndpointSpec.of("POST /leads/assign", BRANCH, (f, s, p) -> withJson(post("/leads/assign"), json(
                        "leadIds", List.of(watched(f, p, f.freshLead(s))),
                        "assignToUserId", f.user(s.org(), Persona.AGENT_OWN_1)))),

                EndpointSpec.of("POST /leads/convert", BRANCH, (f, s, p) -> withJson(post("/leads/convert"), json(
                        "leadId", watched(f, p, f.freshLead(s)), "conversionNotes", "IT matrix"))),

                EndpointSpec.of("POST /leads/mark-lost", BRANCH, (f, s, p) -> withJson(post("/leads/mark-lost"), json(
                        "leadId", watched(f, p, f.freshLead(s)), "lostReasonId", f.lostReason(s), "notes", "IT matrix"))),

                EndpointSpec.of("GET /leads/{id}/timeline", BRANCH, (f, s, p) -> get("/leads/{id}/timeline", f.lead(s))),

                EndpointSpec.of("POST /leads/{id}/notes", BRANCH, (f, s, p) ->
                        withJson(post("/leads/{id}/notes", watchedNotes(f, p, f.freshLead(s))), json("note", "IT matrix note"))),

                EndpointSpec.of("GET /leads/metrics", BRANCH,
                        (f, s, p) -> get("/leads/metrics").param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))));
    }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Lead.class, id, l -> l.getFirstName() + "|" + l.getStatus().getId() + "|"
                + (l.getAssignedTo() == null ? null : l.getAssignedTo().getId()) + "|" + l.getIsConverted()
                + "|" + (l.getLostReason() == null ? null : l.getLostReason().getId())));
        return id;
    }

    private static Long watchedNotes(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Lead.class, id, l -> l.getActivities().size()));
        return id;
    }

    private LeadRows() { }
}
