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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 16 P1 part 2: the streamed audience (P-10, flag broadcast.chunked_tx; the IT profile pages by 7)
 * gives exactly the recipients of the in-memory path; the schedule input is the company's local time (D15);
 * BROADCASTS:SEND follows the Add right; OWN roles are refused when own_scope.enforced is on (D14).
 */
class BroadcastAudienceAndScheduleIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private String savedZone;

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.BROADCAST_CHUNKED_TX, fixture.org('A'));
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        if (savedZone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", savedZone, fixture.company(Slot.A2a));
    }

    private MockHttpServletResponse call(Persona p, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private JsonNode create(Slot s, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("companyId", fixture.company(s), "branchId", fixture.branch(s),
                "whatsappConfigurationId", fixture.config(s), "name", "IT audience", "templateName", "hello_world"));
        body.putAll(extra);
        MockHttpServletResponse r = call(Persona.ORG_ADMIN, post("/broadcasts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
        assertEquals(201, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString());
    }

    private List<Map<String, Object>> recipients(long broadcastId) {
        return jdbc.queryForList("SELECT recipient_phone, recipient_name, customer_id, lead_id, status FROM broadcast_recipients WHERE broadcast_id = ? ORDER BY id", broadcastId);
    }

    private void sameRecipientsBothWays(Map<String, Object> audience) throws Exception {
        JsonNode before = create(Slot.A1a, audience);
        flags.setOverride(FeatureFlag.BROADCAST_CHUNKED_TX, fixture.org('A'), true, "it", "BroadcastAudienceAndScheduleIT");
        JsonNode streamed = create(Slot.A1a, audience);
        flags.clearOverride(FeatureFlag.BROADCAST_CHUNKED_TX, fixture.org('A'));
        List<Map<String, Object>> expected = recipients(before.path("id").asLong());
        assertFalse(expected.isEmpty(), "the audience is not empty: " + audience);
        assertEquals(expected, recipients(streamed.path("id").asLong()), "same recipients, same order: " + audience);
        assertEquals(before.path("totalRecipients").asInt(), streamed.path("totalRecipients").asInt());
        assertEquals(expected.size(), streamed.path("totalRecipients").asInt());
    }

    @Test
    void theStreamedAudienceMatchesTheInMemoryOne() throws Exception {
        for (int i = 0; i < 10; i++) fixture.freshLeadAssignedTo(Slot.A1a, null); // more than one page of 7
        sameRecipientsBothWays(Map.of("audienceType", "ALL_CUSTOMERS"));
        sameRecipientsBothWays(Map.of("audienceType", "ALL_LEADS"));
        List<String> known = jdbc.queryForList("SELECT mobile_normalized FROM customers WHERE company_id = ? AND mobile_normalized IS NOT NULL ORDER BY id LIMIT 9",
                String.class, fixture.company(Slot.A1a));
        List<String> numbers = new ArrayList<>(known);
        numbers.add("971500000001"); // not a customer
        numbers.add(known.get(0));    // a duplicate
        sameRecipientsBothWays(Map.of("audienceType", "CUSTOM_NUMBERS", "customNumbers", numbers));
    }

    @Test
    void theScheduleIsTheCompanysLocalTime() throws Exception {
        Long company = fixture.company(Slot.A2a);
        savedZone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        JsonNode ist = create(Slot.A2a, Map.of("audienceType", "CUSTOM_NUMBERS", "customNumbers", List.of(fixture.phone()), "scheduledAt", "2030-05-10T10:00:00"));
        assertEquals("SCHEDULED", ist.path("status").asText());
        assertEquals(java.sql.Timestamp.valueOf("2030-05-10 08:30:00"),
                jdbc.queryForObject("SELECT scheduled_at FROM broadcasts WHERE id = ?", java.sql.Timestamp.class, ist.path("id").asLong()),
                "10:00 IST is sent at 08:30 server time (Asia/Dubai)");
        assertTrue(ist.path("scheduledAt").asText().startsWith("2030-05-10T08:30"), "the DTO returns server time, like every timestamp");

        JsonNode dubai = create(Slot.A1a, Map.of("audienceType", "CUSTOM_NUMBERS", "customNumbers", List.of(fixture.phone()), "scheduledAt", "2030-05-10T10:00:00"));
        assertEquals(java.sql.Timestamp.valueOf("2030-05-10 10:00:00"),
                jdbc.queryForObject("SELECT scheduled_at FROM broadcasts WHERE id = ?", java.sql.Timestamp.class, dubai.path("id").asLong()),
                "a Dubai company is unchanged");
    }

    @Test
    void whoeverMayCreateABroadcastMaySendIt() throws Exception {
        Long id = fixture.freshBroadcastOnInactiveNumber(Slot.A1a);
        MockHttpServletResponse r = call(Persona.BRANCH_MGR, post("/broadcasts/{id}/send", id));
        assertEquals(200, r.getStatus(), "was 403: BROADCASTS:SEND was never granted. " + r.getContentAsString());
        for (int i = 0; i < 100 && !"FAILED".equals(jdbc.queryForObject("SELECT status FROM broadcasts WHERE id = ?", String.class, id)); i++) Thread.sleep(50);
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM broadcasts WHERE id = ?", String.class, id), "inactive number: failed before any Meta call");
        org.mockito.Mockito.verifyNoInteractions(graphApi);
    }

    @Test
    void ownRolesAreRefusedWhenOwnScopeIsEnforced() throws Exception {
        String company = String.valueOf(fixture.company(Slot.A1a));
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/broadcasts").param("companyId", company)).getStatus(), "flag off: as before");
        flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "BroadcastAudienceAndScheduleIT");
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/broadcasts").param("companyId", company)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/broadcasts/{id}", fixture.broadcast(Slot.A1a))).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/broadcasts/{id}/send", fixture.freshBroadcastOnInactiveNumber(Slot.A1a))).getStatus());
        assertEquals(200, call(Persona.BRANCH_MGR, get("/broadcasts").param("companyId", company).param("branchId", String.valueOf(fixture.branch(Slot.A1a)))).getStatus(),
                "LEVEL managers are unchanged");
    }
}
