package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Delivery;
import com.leads.model.enums.DeliveryStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 09 P1 (D14) with own_scope.enforced ON for org A: OWN-LIST and the unassigned-today count;
 * OWN-RECORD; OWN-WRITE: only the assignee progresses a delivery, OWN users never assign or approve;
 * company-local "today" (D15); and F-12 on delivery settings (flag OFF and ON).
 */
class OwnScopeDeliveriesIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private Long a1, a2;
    private String a1Zone;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
    }

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        flags.clearOverride(FeatureFlag.DELIVERY_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A'));
        if (a1Zone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", a1Zone, fixture.company(Slot.A1a));
    }

    private void enforce() { flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "OwnScopeDeliveriesIT"); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Persona p, MockHttpServletRequestBuilder b, Object body) throws Exception {
        return call(p, b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Set<Long> list(Persona p) throws Exception {
        MockHttpServletResponse r = call(p, get("/deliveries").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("branchId", String.valueOf(fixture.branch(Slot.A1a))).param("size", "200"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        Set<Long> out = new HashSet<>();
        for (JsonNode d : json.readTree(r.getContentAsString()).path("content")) out.add(d.path("id").asLong());
        return out;
    }

    private String status(Long delivery) { return fixture.state(Delivery.class, delivery, d -> String.valueOf(d.getStatus())); }

    @Test
    void anOwnAgentListsDeliveriesAssignedToThemOrOnTheirLeads() throws Exception {
        enforce();
        Long assignedToMe = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a2), a1);
        Long onMyLead = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), a2);
        Long notMine = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a2), a2);
        Set<Long> seen = list(Persona.AGENT_OWN_1);
        assertTrue(seen.containsAll(Set.of(assignedToMe, onMyLead)));
        assertFalse(seen.contains(notMine), "OWN-LIST");
        assertTrue(list(Persona.BRANCH_MGR).containsAll(List.of(assignedToMe, onMyLead, notMine)), "LEVEL unchanged");
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/deliveries/{id}", notMine)).getStatus(), "OWN-RECORD");
    }

    @Test
    void onlyTheAssigneeProgressesADeliveryEvenWhenItIsOnTheirLead() throws Exception {
        enforce();
        Long onMyLeadButA2s = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), a2);
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/deliveries/{id}", onMyLeadButA2s)).getStatus(), "visible through the lead");
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/deliveries/{id}/out-for-delivery", onMyLeadButA2s), Map.of()).getStatus(), "OWN-WRITE");
        assertEquals("SCHEDULED", status(onMyLeadButA2s));

        Long mine = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), a1);
        assertEquals(200, send(Persona.AGENT_OWN_1, post("/deliveries/{id}/out-for-delivery", mine), Map.of()).getStatus());
        assertEquals(200, send(Persona.AGENT_OWN_1, post("/deliveries/{id}/mark-delivered", mine), Map.of()).getStatus());
        assertEquals("DELIVERED", status(mine));
    }

    @Test
    void ownUsersNeverAssignOrApprove() throws Exception {
        enforce();
        Long unassigned = fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), null);
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/deliveries/assign"), Map.of("deliveryIds", List.of(unassigned), "assignToUserId", a1)).getStatus(),
                "no self-claim either (owner decision)");
        Long pending = fixture.freshDeliveryIn(Slot.A1a, DeliveryStatus.PENDING_APPROVAL, a1);
        jdbc.update("UPDATE leads SET assigned_to_id = ? WHERE id = (SELECT lead_id FROM deliveries WHERE id = ?)", a1, pending);
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/deliveries/{id}/approve", pending), Map.of()).getStatus());
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/deliveries/{id}/cancel", pending), Map.of("cancelledReason", "x")).getStatus());
        assertEquals("PENDING_APPROVAL", status(pending));
        assertEquals(200, send(Persona.BRANCH_MGR, post("/deliveries/assign"), Map.of("deliveryIds", List.of(unassigned), "assignToUserId", a1)).getStatus(),
                "LEVEL roles still assign");
    }

    @Test
    void schedulingForTodayUsesTheCompanysDate() throws Exception {
        Long company = fixture.company(Slot.A1a);
        a1Zone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        // Uses the company's own today/yesterday (Asia/Kolkata), whatever the server's date is right now.
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        String companyToday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String companyYesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1).toString();
        Map<String, Object> body = new java.util.HashMap<>(Map.of("leadId", fixture.freshLead(Slot.A1a), "recipientName", "IT",
                "recipientPhone", fixture.phone(), "addressLine1", "IT Street"));
        body.put("scheduledDate", companyToday);
        assertEquals(200, send(Persona.ORG_ADMIN, post("/deliveries"), body).getStatus(), "the company's today is allowed");
        body.put("leadId", fixture.freshLead(Slot.A1a));
        body.put("scheduledDate", companyYesterday);
        assertEquals(400, send(Persona.ORG_ADMIN, post("/deliveries"), body).getStatus(), "the company's yesterday is in the past");
    }

    @Test
    void deliverySettingsWritesFollowTheirFlag() throws Exception {
        Map<String, Object> body = Map.of("reasonText", "IT branch reason");
        String company = String.valueOf(fixture.company(Slot.A1a));
        assertEquals(200, send(Persona.BRANCH_MGR, post("/settings/delivery-failure-reasons").param("companyId", company), body).getStatus(), "flag OFF: as before");
        flags.setOverride(FeatureFlag.DELIVERY_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A'), true, "it", "OwnScopeDeliveriesIT");
        assertEquals(403, send(Persona.BRANCH_MGR, post("/settings/delivery-failure-reasons").param("companyId", company), body).getStatus());
        assertEquals(200, send(Persona.COMPANY_MGR, post("/settings/delivery-failure-reasons").param("companyId", company), body).getStatus());
        assertEquals(200, call(Persona.BRANCH_MGR, get("/settings/delivery-failure-reasons").param("companyId", company)).getStatus(), "reads unchanged");
    }
}
