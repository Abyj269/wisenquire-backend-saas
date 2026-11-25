package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Lead;
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
 * Module 06 P1 (D14) with own_scope.enforced ON for org A: the OWN-RECORD, OWN-LIST, OWN-AGG and
 * OWN-WRITE rows for leads, the self-claim rule, auto-assign on create, and the customer's lead list.
 * AGENT_OWN_1/2 hold an OWN role; the branch manager is LEVEL and must see everything as before.
 * (The shared isolation matrix keeps the flag OFF: turning it on there belongs to each module's own rows.)
 */
class OwnScopeLeadsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private Long a1, a2;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
        flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "OwnScopeLeadsIT");
    }

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        flags.clearOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'));
    }

    private void includeUnassigned(boolean on) {
        flags.setOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'), on, "it", "OwnScopeLeadsIT");
    }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Persona p, MockHttpServletRequestBuilder b, Map<String, ?> body) throws Exception {
        return call(p, b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(MockHttpServletResponse r) throws Exception { return json.readTree(r.getContentAsString()); }

    private JsonNode list(Persona p) throws Exception {
        MockHttpServletResponse r = call(p, get("/leads").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("branchId", String.valueOf(fixture.branch(Slot.A1a))).param("size", "100"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return body(r);
    }

    private Set<Long> ids(JsonNode page) {
        Set<Long> out = new HashSet<>();
        for (JsonNode l : page.path("content")) out.add(l.path("id").asLong());
        return out;
    }

    private long countAssigned(Long user, boolean plusUnassigned) {
        String sql = "SELECT COUNT(*) FROM leads WHERE branch_id = ? AND (assigned_to_id = ?" + (plusUnassigned ? " OR assigned_to_id IS NULL)" : ")");
        return jdbc.queryForObject(sql, Long.class, fixture.branch(Slot.A1a), user);
    }

    // ---- OWN-LIST / OWN-AGG ----------------------------------------------------------------------

    @Test
    void anOwnAgentListsOnlyTheirLeadsAndTheTotalMatches() throws Exception {
        Long mine = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Long theirs = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        Long unassigned = fixture.freshLeadAssignedTo(Slot.A1a, null);

        JsonNode page = list(Persona.AGENT_OWN_1);
        assertTrue(ids(page).contains(mine));
        assertFalse(ids(page).contains(theirs), "OWN-LIST: another agent's lead");
        assertFalse(ids(page).contains(unassigned), "unassigned hidden unless include_unassigned");
        for (JsonNode l : page.path("content")) assertEquals(a1, l.path("assignedToUserId").asLong(), l.toString());
        assertEquals(countAssigned(a1, false), page.path("page").path("totalElements").asLong(), "the total excludes other agents' leads");

        includeUnassigned(true);
        page = list(Persona.AGENT_OWN_1);
        assertTrue(ids(page).contains(unassigned));
        assertFalse(ids(page).contains(theirs));
        assertEquals(countAssigned(a1, true), page.path("page").path("totalElements").asLong());

        assertTrue(ids(list(Persona.BRANCH_MGR)).containsAll(List.of(mine, theirs, unassigned)), "LEVEL roles unchanged");
    }

    @Test
    void theDashboardCountsOnlyTheAgentsLeads() throws Exception {
        fixture.freshLeadAssignedTo(Slot.A1a, a1);
        fixture.freshLeadAssignedTo(Slot.A1a, a2);
        MockHttpServletResponse r = call(Persona.AGENT_OWN_1, get("/leads/metrics").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("branchId", String.valueOf(fixture.branch(Slot.A1a))));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertEquals(countAssigned(a1, false), body(r).path("totalLeads").asLong(), "OWN-AGG");
    }

    // ---- OWN-RECORD -------------------------------------------------------------------------------

    @Test
    void anotherAgentsLeadIsRefusedOnEverySingleLeadEndpoint() throws Exception {
        Long theirs = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/leads/{id}", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/leads/{id}/timeline", theirs)).getStatus());
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/leads/{id}/notes", theirs), Map.of("note", "x")).getStatus());
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/leads/convert"), Map.of("leadId", theirs)).getStatus());
        assertEquals(403, send(Persona.AGENT_OWN_1, post("/leads/mark-lost"), Map.of("leadId", theirs, "lostReasonId", fixture.lostReason(Slot.A1a), "notes", "x")).getStatus());
        assertEquals(403, send(Persona.AGENT_OWN_1, put("/leads/{id}", theirs), Map.of("branchId", fixture.branch(Slot.A1a), "firstName", "X",
                "phone", fixture.state(Lead.class, theirs, Lead::getPhone), "sourceId", fixture.source(Slot.A1a), "statusId", fixture.status(Slot.A1a))).getStatus());
        assertEquals(String.valueOf(a2), fixture.state(Lead.class, theirs, l -> l.getAssignedTo().getId()));
    }

    // ---- OWN-WRITE: assign, self-claim, create ------------------------------------------------------

    private int assign(Persona p, Long lead, Long to) throws Exception {
        return send(p, post("/leads/assign"), Map.of("leadIds", List.of(lead), "assignToUserId", to)).getStatus();
    }

    private String assignee(Long lead) {
        return fixture.state(Lead.class, lead, l -> l.getAssignedTo() == null ? "none" : String.valueOf(l.getAssignedTo().getId()));
    }

    @Test
    void anOwnAgentCannotAssignOthersOrReassign() throws Exception {
        Long mine = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Long unassigned = fixture.freshLeadAssignedTo(Slot.A1a, null);
        includeUnassigned(true);
        assertEquals(403, assign(Persona.AGENT_OWN_1, mine, a2), "hand my lead to someone else");
        assertEquals(403, assign(Persona.AGENT_OWN_1, unassigned, a2), "assign an unassigned lead to someone else");
        assertEquals(String.valueOf(a1), assignee(mine));
        assertEquals("none", assignee(unassigned));
        assertEquals(200, assign(Persona.BRANCH_MGR, unassigned, a2), "LEVEL roles still assign");
    }

    @Test
    void selfClaimNeedsIncludeUnassigned() throws Exception {
        Long unassigned = fixture.freshLeadAssignedTo(Slot.A1a, null);
        assertEquals(403, assign(Persona.AGENT_OWN_1, unassigned, a1), "include_unassigned OFF: not even visible");
        assertEquals("none", assignee(unassigned));
        includeUnassigned(true);
        assertEquals(200, assign(Persona.AGENT_OWN_1, unassigned, a1), "self-claim via /assign");
        assertEquals(String.valueOf(a1), assignee(unassigned));

        Long other = fixture.freshLeadAssignedTo(Slot.A1a, null);
        MockHttpServletResponse r = send(Persona.AGENT_OWN_1, put("/leads/{id}", other), Map.of("branchId", fixture.branch(Slot.A1a), "firstName", "Claimed",
                "phone", fixture.state(Lead.class, other, Lead::getPhone), "sourceId", fixture.source(Slot.A1a), "statusId", fixture.status(Slot.A1a),
                "assignedToUserId", a1));
        assertEquals(200, r.getStatus(), "self-claim via edit: " + r.getContentAsString());
        assertEquals(String.valueOf(a1), assignee(other));
    }

    @Test
    void anOwnAgentCannotUnassignTheirLead() throws Exception {
        Long mine = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("branchId", fixture.branch(Slot.A1a), "firstName", "Mine",
                "phone", fixture.state(Lead.class, mine, Lead::getPhone), "sourceId", fixture.source(Slot.A1a), "statusId", fixture.status(Slot.A1a)));
        assertEquals(403, send(Persona.AGENT_OWN_1, put("/leads/{id}", mine), body).getStatus(), "no assignee = unassign");
        assertEquals(String.valueOf(a1), assignee(mine));
    }

    private MockHttpServletResponse create(Persona p, Long assignTo) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("companyId", fixture.company(Slot.A1a), "branchId", fixture.branch(Slot.A1a),
                "firstName", "IT Own", "phone", fixture.phone(), "sourceId", fixture.source(Slot.A1a)));
        if (assignTo != null) body.put("assignedToUserId", assignTo);
        return send(p, post("/leads"), body);
    }

    @Test
    void aLeadCreatedByAnOwnAgentIsAssignedToThem() throws Exception {
        MockHttpServletResponse r = create(Persona.AGENT_OWN_1, null);
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertEquals(a1, body(r).path("assignedToUserId").asLong(), "auto-assigned to the creator");
        assertEquals(403, create(Persona.AGENT_OWN_1, a2).getStatus(), "never to someone else");

        MockHttpServletResponse mgr = create(Persona.BRANCH_MGR, null);
        assertEquals(200, mgr.getStatus());
        assertTrue(body(mgr).path("assignedToUserId").isNull() || body(mgr).path("assignedToUserId").isMissingNode(), "LEVEL: unassigned as before");
    }

    // ---- a customer's leads --------------------------------------------------------------------------

    @Test
    void aCustomersLeadListShowsOnlyTheAgentsLeads() throws Exception {
        Long mine = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Long customer = fixture.customerOfLead(mine);
        Long theirs = fixture.freshLeadForCustomer(Slot.A1a, customer, a2);
        MockHttpServletResponse r = call(Persona.AGENT_OWN_1, get("/customers/{id}/leads", customer));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(ids(body(r)).contains(mine));
        assertFalse(ids(body(r)).contains(theirs), "the same customer's lead of another agent");
        assertTrue(ids(body(call(Persona.BRANCH_MGR, get("/customers/{id}/leads", customer)))).contains(theirs));
    }
}
