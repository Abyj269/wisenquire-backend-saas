package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Customer;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 07 P1: F-11 (the phone typeahead respects branch scope), the D14 OWN rule for customers
 * (list, typeahead and single-customer endpoints agree), the chat half behind
 * own_scope.customer_via_chats, and company-local date filters (D15).
 */
class OwnScopeCustomersIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private Long a1, a2;
    private String a2Zone;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
    }

    @AfterEach
    void tearDown() {
        for (FeatureFlag f : new FeatureFlag[]{FeatureFlag.OWN_SCOPE_ENFORCED, FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS}) {
            flags.clearOverride(f, fixture.org('A'));
        }
        if (a2Zone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", a2Zone, fixture.company(Slot.A2a));
    }

    private void on(FeatureFlag f) { flags.setOverride(f, fixture.org('A'), true, "it", "OwnScopeCustomersIT"); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private Set<Long> ids(JsonNode list) {
        Set<Long> out = new HashSet<>();
        for (JsonNode c : list.has("content") ? list.path("content") : list) out.add(c.path("id").asLong());
        return out;
    }

    private Set<Long> customers(Persona p, Slot s) throws Exception {
        MockHttpServletResponse r = call(p, get("/customers").param("companyId", String.valueOf(fixture.company(s))).param("size", "200"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return ids(json.readTree(r.getContentAsString()));
    }

    private Set<Long> suggest(Persona p, Long customer) throws Exception {
        String mobile = fixture.state(Customer.class, customer, Customer::getMobileNormalized);
        MockHttpServletResponse r = call(p, get("/customers/suggest").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("phone", mobile.substring(mobile.length() - 7)));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return ids(json.readTree(r.getContentAsString()));
    }

    // ---- F-11 ---------------------------------------------------------------------------------------

    @Test
    void theTypeaheadOnlySuggestsCustomersOfTheCallersBranches() throws Exception {
        Long onlyInA1b = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1b, null));
        assertFalse(suggest(Persona.BRANCH_MGR, onlyInA1b).contains(onlyInA1b), "F-11: a customer seen only in A1b");
        assertTrue(suggest(Persona.COMPANY_MGR, onlyInA1b).contains(onlyInA1b), "company-level users still see it");
        Long inA1a = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1a, null));
        assertTrue(suggest(Persona.BRANCH_MGR, inA1a).contains(inA1a));
    }

    // ---- OWN ----------------------------------------------------------------------------------------

    @Test
    void anOwnAgentSeesOnlyCustomersOfTheirLeads() throws Exception {
        on(FeatureFlag.OWN_SCOPE_ENFORCED);
        Long mine = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1a, a1));
        Long theirs = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1a, a2));
        Long unassigned = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1a, null));

        Set<Long> list = customers(Persona.AGENT_OWN_1, Slot.A1a);
        assertTrue(list.contains(mine));
        assertFalse(list.contains(theirs), "OWN-LIST");
        assertFalse(list.contains(unassigned));
        assertFalse(suggest(Persona.AGENT_OWN_1, theirs).contains(theirs), "the typeahead follows the same rule");
        assertTrue(suggest(Persona.AGENT_OWN_1, mine).contains(mine));

        assertEquals(403, call(Persona.AGENT_OWN_1, get("/customers/{id}", theirs)).getStatus(), "OWN-RECORD");
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/customers/{id}/logs", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/customers/{id}/notes", theirs).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}")).getStatus());
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/customers/{id}", mine)).getStatus());

        on(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED);
        assertTrue(customers(Persona.AGENT_OWN_1, Slot.A1a).contains(unassigned));
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/customers/{id}", unassigned)).getStatus());

        assertTrue(customers(Persona.BRANCH_MGR, Slot.A1a).containsAll(Set.of(mine, theirs, unassigned)), "LEVEL roles unchanged");
    }

    @Test
    void aCustomerWithLeadsOfBothAgentsIsVisibleToBoth() throws Exception {
        on(FeatureFlag.OWN_SCOPE_ENFORCED);
        Long lead1 = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Long customer = fixture.customerOfLead(lead1);
        fixture.freshLeadForCustomer(Slot.A1a, customer, a2);
        assertTrue(customers(Persona.AGENT_OWN_1, Slot.A1a).contains(customer));
        assertTrue(customers(Persona.AGENT_OWN_2, Slot.A1a).contains(customer));
    }

    @Test
    void theChatHalfFollowsItsFlag() throws Exception {
        on(FeatureFlag.OWN_SCOPE_ENFORCED);
        Long lead = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        Long customer = fixture.customerOfLead(lead);
        fixture.freshConversationFor(Slot.A1a, a1, lead); // a chat assigned to agent 1 on agent 2's lead

        // Default ON since V75 (module 12 P1 done); switched OFF for this org first to prove the switch.
        flags.setOverride(FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS, fixture.org('A'), false, "it", "OwnScopeCustomersIT");
        assertFalse(customers(Persona.AGENT_OWN_1, Slot.A1a).contains(customer), "flag OFF: not through a chat");
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/customers/{id}", customer)).getStatus(), "record and list agree");

        on(FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS);
        assertTrue(customers(Persona.AGENT_OWN_1, Slot.A1a).contains(customer), "flag ON: visible through the chat");
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/customers/{id}", customer)).getStatus());
    }

    // ---- D15 ----------------------------------------------------------------------------------------

    @Test
    void theDateFilterUsesTheCompanysDay() throws Exception {
        Long company = fixture.company(Slot.A2a);
        a2Zone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        // freshLeadAssignedTo makes a NEW customer each time (freshLead reuses the slot's shared one).
        Long before = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A2a, null)), after = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A2a, null));
        fixture.setCreatedAt("customers", before, java.time.LocalDateTime.parse("2026-03-10T22:29:00"));
        fixture.setCreatedAt("customers", after, java.time.LocalDateTime.parse("2026-03-10T22:31:00"));
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);

        MockHttpServletResponse r = call(Persona.ORG_ADMIN, get("/customers").param("companyId", String.valueOf(company))
                .param("fromDate", "2026-03-10").param("toDate", "2026-03-10").param("size", "200"));
        Set<Long> day = ids(json.readTree(r.getContentAsString()));
        assertTrue(day.contains(before), "23:59 IST is the 10th");
        assertFalse(day.contains(after), "00:01 IST is the 11th");
    }
}
