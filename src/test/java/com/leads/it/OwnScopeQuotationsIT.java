package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Quotation;
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
 * Module 08 P1 (D14) with own_scope.enforced ON for org A: an OWN agent sees and acts on a quotation
 * when its lead is visible to them OR they created it (OWN-LIST, OWN-RECORD), managers unchanged;
 * and the list's date filter in the company's day (D15).
 */
class OwnScopeQuotationsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private Long a1, a2, mgr;
    private String a2Zone;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
        mgr = fixture.user('A', Persona.BRANCH_MGR);
    }

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        if (a2Zone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", a2Zone, fixture.company(Slot.A2a));
    }

    private void enforce() { flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "OwnScopeQuotationsIT"); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private JsonNode list(Persona p, Slot s, String day) throws Exception {
        MockHttpServletRequestBuilder b = get("/quotations").param("companyId", String.valueOf(fixture.company(s))).param("size", "100");
        if (day != null) b = b.param("fromDate", day).param("toDate", day);
        MockHttpServletResponse r = call(p, b);
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString());
    }

    private Set<Long> ids(JsonNode page) {
        Set<Long> out = new HashSet<>();
        for (JsonNode q : page.path("content")) out.add(q.path("id").asLong());
        return out;
    }

    @Test
    void anOwnAgentListsQuotationsOfTheirLeadsOrThatTheyCreated() throws Exception {
        enforce();
        Long onMyLead = fixture.freshQuotation(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), mgr);
        Long createdByMe = fixture.freshQuotation(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a2), a1);
        Long notMine = fixture.freshQuotation(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a2), mgr);

        JsonNode page = list(Persona.AGENT_OWN_1, Slot.A1a, null);
        Set<Long> seen = ids(page);
        assertTrue(seen.containsAll(Set.of(onMyLead, createdByMe)), "by lead or by creator");
        assertFalse(seen.contains(notMine), "OWN-LIST");
        long expected = jdbc.queryForObject("SELECT COUNT(*) FROM quotations q JOIN leads l ON l.id = q.lead_id"
                + " WHERE q.company_id = ? AND (l.assigned_to_id = ? OR q.created_by_id = ?)", Long.class, fixture.company(Slot.A1a), a1, a1);
        assertEquals(expected, page.path("page").path("totalElements").asLong(), "the total counts only theirs");

        assertTrue(ids(list(Persona.BRANCH_MGR, Slot.A1a, null)).containsAll(List.of(onMyLead, createdByMe, notMine)), "LEVEL unchanged");
    }

    @Test
    void anotherAgentsQuotationIsRefusedEverywhere() throws Exception {
        enforce();
        Long lead = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        Long notMine = fixture.freshQuotation(Slot.A1a, lead, mgr);
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/quotations/{id}", notMine)).getStatus(), "OWN-RECORD");
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/quotations/{id}/pdf", notMine)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/quotations/{id}/send-whatsapp", notMine)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, put("/quotations/{id}", notMine).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("leadId", lead, "items", List.of(Map.of("itemName", "x", "quantity", 1, "unitPrice", 1)))))).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, delete("/quotations/{id}", notMine)).getStatus());
        assertTrue(fixture.exists(Quotation.class, notMine));

        Long createdByMe = fixture.freshQuotation(Slot.A1a, lead, a1);
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/quotations/{id}", createdByMe)).getStatus(), "the creator keeps access");
        assertEquals(200, call(Persona.BRANCH_MGR, get("/quotations/{id}", notMine)).getStatus(), "LEVEL unchanged");
    }

    @Test
    void theListDateFilterUsesTheCompanysDay() throws Exception {
        Long company = fixture.company(Slot.A2a);
        a2Zone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        Long before = fixture.freshQuotation(Slot.A2a, fixture.freshLead(Slot.A2a), null);
        Long after = fixture.freshQuotation(Slot.A2a, fixture.freshLead(Slot.A2a), null);
        fixture.setCreatedAt("quotations", before, java.time.LocalDateTime.parse("2026-03-10T22:29:00"));
        fixture.setCreatedAt("quotations", after, java.time.LocalDateTime.parse("2026-03-10T22:31:00"));
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        Set<Long> day = ids(list(Persona.ORG_ADMIN, Slot.A2a, "2026-03-10"));
        assertTrue(day.contains(before), "23:59 IST is the 10th");
        assertFalse(day.contains(after), "00:01 IST is the 11th");
    }
}
