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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Module 06 P1: date filters in the company's day (D15), and document numbers with the company code. */
class LeadClockAndNumberIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FeatureFlags flags;
    private final ObjectMapper json = new ObjectMapper();
    private String a2Zone;

    @AfterEach
    void tearDown() {
        if (a2Zone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", a2Zone, fixture.company(Slot.A2a));
        flags.clearOverride(FeatureFlag.DOCUMENTS_COMPANY_CODE_PREFIX, fixture.org('A'));
    }

    private Set<Long> leadsOn(Slot s, String day) {
        try { return leadsOnChecked(s, day); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Set<Long> leadsOnChecked(Slot s, String day) throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/leads").param("companyId", String.valueOf(fixture.company(s)))
                .param("fromDate", day).param("toDate", day).param("size", "200")
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        Set<Long> out = new HashSet<>();
        for (JsonNode l : json.readTree(r.getContentAsString()).path("content")) out.add(l.path("id").asLong());
        return out;
    }

    @Test
    void anIndianCompanysDayStartsAtIndianMidnight() throws Exception {
        Long company = fixture.company(Slot.A2a);
        a2Zone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        // Stored times are server wall-clock (Asia/Dubai). 22:29 Dubai = 23:59 IST on the 10th; 22:31 Dubai = 00:01 IST on the 11th.
        Long before = fixture.freshLead(Slot.A2a), after = fixture.freshLead(Slot.A2a);
        fixture.setLeadCreatedAt(before, java.time.LocalDateTime.parse("2026-03-10T22:29:00"));
        fixture.setLeadCreatedAt(after, java.time.LocalDateTime.parse("2026-03-10T22:31:00"));

        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        assertTrue(leadsOn(Slot.A2a, "2026-03-10").contains(before), "23:59 IST is still the 10th");
        assertFalse(leadsOn(Slot.A2a, "2026-03-10").contains(after), "00:01 IST is already the 11th");
        assertTrue(leadsOn(Slot.A2a, "2026-03-11").contains(after));

        jdbc.update("UPDATE companies SET timezone = 'Asia/Dubai' WHERE id = ?", company);
        assertTrue(leadsOn(Slot.A2a, "2026-03-10").containsAll(Set.of(before, after)), "Dubai companies: unchanged (server zone)");
    }

    private String newLeadNumber() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "companyId", fixture.company(Slot.A1a), "branchId", fixture.branch(Slot.A1a), "firstName", "IT Number",
                        "phone", fixture.phone(), "sourceId", fixture.source(Slot.A1a))))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString()).path("leadNumber").asText();
    }

    @Test
    void withTheFlagOnNumbersCarryTheCompanyCodeNotItsId() throws Exception {
        Long company = fixture.company(Slot.A1a);
        String code = jdbc.queryForObject("SELECT document_code FROM companies WHERE id = ?", String.class, company);
        assertTrue(code.matches("[0-9A-Z]{6}"), code);

        assertTrue(newLeadNumber().startsWith("LD-" + company + "-"), "flag OFF: the old format");

        flags.setOverride(FeatureFlag.DOCUMENTS_COMPANY_CODE_PREFIX, fixture.org('A'), true, "it", "LeadClockAndNumberIT");
        String number = newLeadNumber();
        assertTrue(number.matches("LD-" + code + "-\\d{4}-\\d{6}"), number);
        assertFalse(number.contains("-" + company + "-"), "no internal id in the number");
    }

    @Test
    void everyCompanyHasAUniqueCode() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE document_code IS NULL", Integer.class));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM companies", Integer.class),
                jdbc.queryForObject("SELECT COUNT(DISTINCT document_code) FROM companies", Integer.class));
    }
}
