package com.leads.it.matrix;

import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.Customer;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 07 Customers: all 8 endpoints. A customer is in a branch's scope when it has a lead
 * there (TenantAccess.record), so every fresh customer comes with a lead in the target slot.
 */
final class CustomerRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("PUT /customers/{id}", BRANCH, (f, s, p) -> withJson(
                        put("/customers/{id}", watched(f, p, f.freshCustomer(s))),
                        json("name", "IT Renamed Customer", "mobileNumber", f.phone()))),

                EndpointSpec.of("GET /customers/{id}", BRANCH, (f, s, p) -> get("/customers/{id}", f.customer(s))),

                EndpointSpec.of("GET /customers", BRANCH,
                        (f, s, p) -> get("/customers").param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),

                EndpointSpec.of("PUT /customers/{id}/unblock", BRANCH,
                        (f, s, p) -> put("/customers/{id}/unblock", watched(f, p, f.freshCustomer(s)))),

                // companyId only. Whether a branch-level user may look up customers of other
                // branches of its own company is an OWN/branch question for module 07.
                EndpointSpec.of("GET /customers/suggest", COMPANY,
                        (f, s, p) -> get("/customers/suggest").param("companyId", str(f.company(s))).param("phone", "97150")),

                EndpointSpec.of("GET /customers/{id}/leads", BRANCH, (f, s, p) -> get("/customers/{id}/leads", f.customer(s))),

                EndpointSpec.of("GET /customers/{id}/logs", BRANCH, (f, s, p) -> get("/customers/{id}/logs", f.customer(s))),

                EndpointSpec.of("POST /customers/{id}/notes", BRANCH, (f, s, p) -> withJson(
                        post("/customers/{id}/notes", watched(f, p, f.freshCustomer(s))), json("note", "IT matrix note"))));
    }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Customer.class, id, c -> c.getName() + "|" + c.getMobileNumber() + "|" + c.getIsBlocked()
                + "|" + c.getUpdatedAt()));
        return id;
    }

    private CustomerRows() { }
}
