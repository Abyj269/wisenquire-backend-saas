package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.Delivery;
import com.leads.model.enums.DeliveryStatus;

import java.time.LocalDate;
import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 09 P1: the 14 DeliveryController endpoints (all BRANCH scope: a delivery has its own branch).
 * Every write gets a fresh delivery in the state its action needs, assigned to the slot's agent, with
 * a probe. OWN rows (actor == assignee, no assign/approve) are in OwnScopeDeliveriesIT.
 */
final class DeliveryRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("POST /deliveries", BRANCH, (f, s, p) -> withJson(post("/deliveries"), json(
                        "leadId", f.freshLead(s), "scheduledDate", LocalDate.now().plusDays(2).toString(),
                        "recipientName", "IT Recipient", "recipientPhone", f.phone(), "addressLine1", "IT Street 1"))),
                EndpointSpec.of("PUT /deliveries/{id}", BRANCH, (f, s, p) -> {
                    Long id = watched(f, p, in(f, s, DeliveryStatus.SCHEDULED));
                    return withJson(put("/deliveries/{id}", id), json("scheduledDate", LocalDate.now().plusDays(3).toString(),
                            "assignedToUserId", agent(f, s), "recipientName", "IT Renamed", "recipientPhone", f.phone(), "addressLine1", "IT Street 2"));
                }),
                EndpointSpec.of("GET /deliveries/{id}", BRANCH, (f, s, p) -> get("/deliveries/{id}", in(f, s, DeliveryStatus.SCHEDULED))),
                EndpointSpec.of("GET /deliveries", BRANCH, (f, s, p) -> get("/deliveries")
                        .param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),
                EndpointSpec.of("GET /deliveries/unassigned-today-count", BRANCH, (f, s, p) -> get("/deliveries/unassigned-today-count")
                        .param("companyId", str(f.company(s))).param("branchId", str(f.branch(s)))),
                EndpointSpec.of("POST /deliveries/assign", BRANCH, (f, s, p) -> withJson(post("/deliveries/assign"), json(
                        "deliveryIds", List.of(watched(f, p, f.freshDeliveryIn(s, DeliveryStatus.SCHEDULED, null))), "assignToUserId", agent(f, s)))),
                action("out-for-delivery", DeliveryStatus.SCHEDULED, "{}"),
                action("mark-delivered", DeliveryStatus.OUT_FOR_DELIVERY, "{}"),
                EndpointSpec.of("POST /deliveries/{id}/mark-not-delivered", BRANCH, (f, s, p) -> withJson(
                        post("/deliveries/{id}/mark-not-delivered", watched(f, p, in(f, s, DeliveryStatus.OUT_FOR_DELIVERY))),
                        json("failureReasonId", f.failureReason(s), "failureNotes", "IT"))),
                EndpointSpec.of("POST /deliveries/{id}/request-postpone", BRANCH, (f, s, p) -> withJson(
                        post("/deliveries/{id}/request-postpone", watched(f, p, in(f, s, DeliveryStatus.NOT_DELIVERED))),
                        json("requestedNewDate", LocalDate.now().plusDays(5).toString(), "requestNotes", "IT"))),
                action("request-close", DeliveryStatus.NOT_DELIVERED, json("requestNotes", "IT close")),
                action("approve", DeliveryStatus.PENDING_APPROVAL, "{}"),
                action("reject", DeliveryStatus.PENDING_APPROVAL, json("approvalRejectionNotes", "IT no")),
                action("cancel", DeliveryStatus.SCHEDULED, json("cancelledReason", "IT cancel")),
                EndpointSpec.of("GET /deliveries/{id}/timeline", BRANCH, (f, s, p) -> get("/deliveries/{id}/timeline", in(f, s, DeliveryStatus.SCHEDULED))));
    }

    /** POST /deliveries/{id}/<name> on a fresh delivery in {@code status}, assigned to the slot's agent. */
    private static EndpointSpec action(String name, DeliveryStatus status, String body) {
        return EndpointSpec.of("POST /deliveries/{id}/" + name, BRANCH, (f, s, p) ->
                withJson(post("/deliveries/{id}/" + name, watched(f, p, in(f, s, status))), body));
    }

    private static Long in(TenantFixture f, Slot s, DeliveryStatus status) { return f.freshDeliveryIn(s, status, agent(f, s)); }

    /** The slot's org agent (an assignee the delivery's branch accepts in the home slots; elsewhere the call is refused anyway). */
    private static Long agent(TenantFixture f, Slot s) { return f.user(s.org(), Persona.AGENT_OWN_1); }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(Delivery.class, id, d -> d.getStatus() + "|" + d.getRequestedAction() + "|" + d.getScheduledDate()
                + "|" + (d.getAssignedTo() == null ? null : d.getAssignedTo().getId()) + "|" + d.getRecipientName()));
        return id;
    }

    private DeliveryRows() { }
}
