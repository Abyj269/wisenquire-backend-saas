package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.SavedReply;
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

/** Module 15 P1: saved-reply writes follow F-12 (lead_settings.company_wide_writes) OFF and ON; reads stay open. */
class SavedReplyWriteScopeIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() { flags.clearOverride(FeatureFlag.LEAD_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A')); }

    private String company() { return String.valueOf(fixture.company(Slot.A1a)); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private int create(Persona p) throws Exception {
        return call(p, post("/settings/saved-replies").param("companyId", company()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "IT scope", "body", "Hello", "active", true)))).getStatus();
    }

    private int rename(Persona p, Long id) throws Exception {
        return call(p, put("/settings/saved-replies/{id}", id).param("companyId", company()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "IT renamed", "body", "Hi", "active", true)))).getStatus();
    }

    @Test
    void withTheFlagOffBranchLevelEditorsKeepTheirRights() throws Exception {
        assertEquals(200, create(Persona.BRANCH_MGR), "old behaviour (flag default OFF)");
        assertEquals(200, rename(Persona.BRANCH_MGR, fixture.freshSavedReply(Slot.A1a)));
    }

    @Test
    void withTheFlagOnOnlyCompanyWideUsersWrite() throws Exception {
        flags.setOverride(FeatureFlag.LEAD_SETTINGS_COMPANY_WIDE_WRITES, fixture.org('A'), true, "it", "SavedReplyWriteScopeIT");
        assertEquals(403, create(Persona.BRANCH_MGR));
        assertEquals(403, create(Persona.AGENT_OWN_1));
        assertEquals(200, create(Persona.COMPANY_MGR));
        Long reply = fixture.freshSavedReply(Slot.A1a);
        assertEquals(403, rename(Persona.BRANCH_MGR, reply));
        assertEquals(403, call(Persona.BRANCH_MGR, delete("/settings/saved-replies/{id}", reply).param("companyId", company())).getStatus());
        assertTrue(fixture.exists(SavedReply.class, reply), "a refused delete leaves the reply");
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/settings/saved-replies").param("companyId", company())).getStatus(),
                "every agent still reads saved replies");
    }

    @Test
    void aReplyCannotBeEditedThroughAnotherCompanyOfTheSameOrg() throws Exception {
        Long reply = fixture.freshSavedReply(Slot.A2a);
        // The org admin may see both companies, but the reply belongs to A2, not the A1 named in the request.
        assertEquals(403, rename(Persona.ORG_ADMIN, reply));
        assertEquals(200, call(Persona.ORG_ADMIN, put("/settings/saved-replies/{id}", reply).param("companyId", String.valueOf(fixture.company(Slot.A2a)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "IT A2", "body", "Hi", "active", true)))).getStatus());
    }
}
