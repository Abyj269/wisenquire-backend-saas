package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Module 10 P1: OWN aggregate rules (staff performance only me; customers, lead sources, branches
 * 403 for OWN users) and the customer report's branch rule ("has a lead in the branch").
 * The fixture's agents hold REPORTS_ANALYTICS (a misconfigured role, which is what the rule guards).
 */
class OwnScopeReportsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() { flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A')); }

    private void enforce() { flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "OwnScopeReportsIT"); }

    private MockHttpServletResponse report(Persona p, String path) throws Exception {
        return mvc.perform(get("/reports/" + path).param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("branchId", String.valueOf(fixture.branch(Slot.A1a)))
                .header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    @Test
    void anOwnAgentGetsOnlyTheirOwnNumbersAndNoCompanyAggregates() throws Exception {
        Long a1 = fixture.user('A', Persona.AGENT_OWN_1), a2 = fixture.user('A', Persona.AGENT_OWN_2);
        fixture.freshLeadAssignedTo(Slot.A1a, a1);
        fixture.freshLeadAssignedTo(Slot.A1a, a2);
        assertEquals(200, report(Persona.AGENT_OWN_1, "lead-sources").getStatus(), "flag OFF: as before");

        enforce();
        MockHttpServletResponse staff = report(Persona.AGENT_OWN_1, "staff-performance");
        assertEquals(200, staff.getStatus(), staff.getContentAsString());
        Set<Long> users = new HashSet<>();
        for (JsonNode s : json.readTree(staff.getContentAsString()).path("staff")) users.add(s.path("userId").asLong());
        assertEquals(Set.of(a1), users, "OWN-AGG: only the agent's own row");
        assertEquals(200, report(Persona.AGENT_OWN_1, "response-time").getStatus());
        assertEquals(200, report(Persona.AGENT_OWN_1, "revenue-forecast").getStatus());

        for (String company : new String[]{"customers", "lead-sources"}) {
            assertEquals(403, report(Persona.AGENT_OWN_1, company).getStatus(), company + " is a company aggregate");
        }
        assertEquals(403, mvc.perform(get("/reports/branches").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.AGENT_OWN_1))).andReturn().getResponse().getStatus());

        MockHttpServletResponse mgr = report(Persona.BRANCH_MGR, "staff-performance");
        Set<Long> all = new HashSet<>();
        for (JsonNode s : json.readTree(mgr.getContentAsString()).path("staff")) all.add(s.path("userId").asLong());
        assertTrue(all.containsAll(Set.of(a1, a2)), "LEVEL roles unchanged");
        assertEquals(200, report(Persona.BRANCH_MGR, "customers").getStatus());
    }

    @Test
    void aBranchsCustomersAreThoseWithALeadThereNotOnlyItsHomeCustomers() throws Exception {
        // Home branch A1b (first lead there), then a lead in A1a: it now counts for A1a.
        Long customer = fixture.customerOfLead(fixture.freshLeadAssignedTo(Slot.A1b, null));
        fixture.freshLeadForCustomer(Slot.A1a, customer, null);
        jdbc.update("UPDATE customers SET first_lead_at = NOW() WHERE id = ?", customer);

        MockHttpServletResponse r = report(Persona.ORG_ADMIN, "customers");
        assertEquals(200, r.getStatus(), r.getContentAsString());
        Set<Long> acquired = new HashSet<>();
        for (JsonNode c : json.readTree(r.getContentAsString()).path("newlyAcquired")) acquired.add(c.path("id").asLong());
        assertTrue(acquired.contains(customer), "has a lead in A1a, same as the customer list");
    }
}
