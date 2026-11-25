package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Module 04 P1: F-10 regression and F-12 (lead_settings.company_wide_writes) with the flag OFF and ON. */
class LeadSettingsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() { flags.clearOverride(FeatureFlag.LEAD_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A')); }

    private void companyWideWrites(boolean on) {
        flags.setOverride(FeatureFlag.LEAD_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A'), on, "it", "LeadSettingsIT");
    }

    private MockHttpServletResponse send(Persona p, MockHttpServletRequestBuilder b, Map<String, ?> body) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
    }

    private MockHttpServletResponse createPurpose(Persona p) throws Exception {
        return send(p, post("/settings/purposes").param("companyId", String.valueOf(fixture.company(Slot.A1a))),
                Map.of("purposeName", "IT Purpose " + fixture.phone().substring(6), "isActive", false)); // inactive: keeps purpose optional for other ITs
    }

    private MockHttpServletResponse setDefaultStatus(Long statusId) throws Exception {
        return send(Persona.ORG_ADMIN, put("/settings/company-lead-settings").param("companyId", String.valueOf(fixture.company(Slot.A1a))),
                Map.of("linkProductEnabled", true, "autoAssignEnabled", false, "defaultStatusId", statusId));
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** A1's settings row and its default status, to prove a refused PUT changed nothing. */
    private String defaultStatusOfA1() {
        Long company = fixture.company(Slot.A1a);
        return fixture.companyLeadSettingsState(company) + "|"
                + jdbc.queryForList("SELECT default_status_id FROM company_lead_settings WHERE company_id = ?", Long.class, company);
    }

    // ---- F-10 regression -----------------------------------------------------------------------

    @Test
    void anotherOrgsStatusCannotBecomeTheDefault() throws Exception {
        String before = defaultStatusOfA1();
        MockHttpServletResponse r = setDefaultStatus(fixture.status(Slot.B1a));
        assertEquals(403, r.getStatus(), r.getContentAsString());
        assertFalse(r.getContentAsString().contains("New"), "nothing of org B's status is echoed back");
        assertEquals(before, defaultStatusOfA1());
    }

    @Test
    void anotherCompanysStatusInTheSameOrgIsRejected() throws Exception {
        String before = defaultStatusOfA1();
        assertEquals(400, setDefaultStatus(fixture.status(Slot.A2a)).getStatus());
        assertEquals(before, defaultStatusOfA1());
    }

    @Test
    void ownCompanysStatusIsAccepted() throws Exception {
        assertEquals(200, setDefaultStatus(fixture.status(Slot.A1a)).getStatus());
    }

    // ---- updates without the optional NOT NULL fields (was 500 on the column constraint) ----------

    @Test
    void anUpdateThatLeavesOutOptionalFieldsKeepsTheirValues() throws Exception {
        Long status = fixture.freshStatus(Slot.A1a);
        MockHttpServletResponse r = send(Persona.ORG_ADMIN, put("/settings/statuses/{id}", status), Map.of("statusName", "IT Minimal",
                "statusCode", fixture.state(com.leads.model.entity.LeadStatus.class, status, com.leads.model.entity.LeadStatus::getStatusCode),
                "stageType", "NEGOTIATION", "colorCode", "#10B981")); // colorCode is @NotBlank on the DTO: missing = 400 by validation
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertEquals("IT Minimal|#10B981|false|true", fixture.state(com.leads.model.entity.LeadStatus.class, status,
                x -> x.getStatusName() + "|" + x.getColorCode() + "|" + x.getIsDefault() + "|" + x.getIsActive()));

        Long purpose = fixture.freshPurpose(Slot.A1a);
        assertEquals(200, send(Persona.ORG_ADMIN, put("/settings/purposes/{id}", purpose), Map.of("purposeName", "IT Minimal")).getStatus());
        assertEquals("false|0", fixture.state(com.leads.model.entity.LeadPurpose.class, purpose, x -> x.getIsActive() + "|" + x.getSortOrder()));

        Long reason = fixture.freshLostReason(Slot.A1a);
        assertEquals(200, send(Persona.ORG_ADMIN, put("/settings/lost-reasons/{id}", reason), Map.of("reasonText", "IT Minimal")).getStatus());
        assertEquals("true|0", fixture.state(com.leads.model.entity.LeadLostReason.class, reason, x -> x.getIsActive() + "|" + x.getSortOrder()));

        Long source = fixture.freshSource(Slot.A1a);
        MockHttpServletResponse s = send(Persona.ORG_ADMIN, put("/settings/sources/{id}", source), Map.of("sourceName", "IT Minimal",
                "sourceCode", fixture.state(com.leads.model.entity.LeadSource.class, source, com.leads.model.entity.LeadSource::getSourceCode)));
        assertEquals(200, s.getStatus(), s.getContentAsString());
        assertEquals("#6366F1|true|0", fixture.state(com.leads.model.entity.LeadSource.class, source, x -> x.getColorCode() + "|" + x.getIsActive() + "|" + x.getSortOrder()));

        // Company settings: send one field at a time; the others keep their values. Restored at the end.
        String company = String.valueOf(fixture.company(Slot.A1a));
        assertEquals(200, send(Persona.ORG_ADMIN, put("/settings/company-lead-settings").param("companyId", company), Map.of("linkProductEnabled", false)).getStatus());
        assertEquals(200, send(Persona.ORG_ADMIN, put("/settings/company-lead-settings").param("companyId", company), Map.of("customerLostAfterDays", 60)).getStatus());
        assertTrue(fixture.companyLeadSettingsState(fixture.company(Slot.A1a)).startsWith("[false|false|"), "link-product and auto-assign kept");
        assertEquals(200, send(Persona.ORG_ADMIN, put("/settings/company-lead-settings").param("companyId", company), Map.of("linkProductEnabled", true)).getStatus());
    }

    // ---- F-12 ---------------------------------------------------------------------------------

    @Test
    void withTheFlagOffBranchLevelEditorsKeepTheirRights() throws Exception {
        companyWideWrites(false);
        assertEquals(200, createPurpose(Persona.BRANCH_MGR).getStatus(), "old behaviour");
    }

    @Test
    void withTheFlagOnOnlyCompanyWideUsersWrite() throws Exception {
        companyWideWrites(true);
        for (Persona p : new Persona[]{Persona.BRANCH_MGR, Persona.AGENT_OWN_1}) {
            MockHttpServletResponse r = createPurpose(p);
            assertEquals(403, r.getStatus(), p + " " + r.getContentAsString());
            assertEquals("FORBIDDEN", json.readTree(r.getContentAsString()).path("code").asText());
        }
        assertEquals(200, createPurpose(Persona.COMPANY_MGR).getStatus());
        assertEquals(200, createPurpose(Persona.ORG_ADMIN).getStatus());

        Long status = fixture.freshStatus(Slot.A1a);
        MockHttpServletResponse del = mvc.perform(delete("/settings/statuses/{id}", status)
                .header("Authorization", "Bearer " + fixture.token('A', Persona.BRANCH_MGR))).andReturn().getResponse();
        assertEquals(403, del.getStatus(), "record-based writes too");
        assertTrue(fixture.exists(com.leads.model.entity.LeadStatus.class, status));
    }

    @Test
    void withTheFlagOnEveryoneStillReads() throws Exception {
        companyWideWrites(true);
        for (Persona p : Persona.values()) {
            int status = mvc.perform(get("/settings/statuses").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                    .header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse().getStatus();
            assertEquals(200, status, p.toString());
        }
    }
}
