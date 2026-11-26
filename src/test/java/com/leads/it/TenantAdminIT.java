package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.Company;
import com.leads.model.entity.Organization;
import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.DataScope;
import com.leads.repository.OrganizationRepository;
import com.leads.service.OrganizationSlugs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Module 03 P1 behaviour beyond the isolation matrix: slim user lists, D15 zones, role data scope, slugs. */
class TenantAdminIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired OrganizationRepository orgs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() { flags.clearOverride(FeatureFlag.TENANT_TIMEZONE_ALLOWLIST, fixture.org('A')); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Persona p, MockHttpServletRequestBuilder b, Map<String, ?> body) throws Exception {
        return call(p, b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    // ---- slim users list ------------------------------------------------------------------------

    private JsonNode users(Persona p) throws Exception {
        MockHttpServletResponse r = call(p, get("/tenant/users").param("companyId", String.valueOf(fixture.company(Slot.A1a))));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        JsonNode list = json.readTree(r.getContentAsString());
        assertTrue(list.size() > 0);
        return list;
    }

    @Test
    void pickerCallersGetOnlyNamesWhileAdminsGetTheFullUser() throws Exception {
        for (JsonNode u : users(Persona.AGENT_OWN_1)) {
            assertTrue(u.hasNonNull("id") && u.hasNonNull("fullName"), u.toString());
            for (String hidden : List.of("email", "phone", "isSuperAdmin", "allowedCompanyIds", "allowedBranchIds", "organizationId")) {
                assertFalse(u.hasNonNull(hidden), "agent sees " + hidden + ": " + u);
            }
        }
        assertTrue(users(Persona.ORG_ADMIN).get(0).hasNonNull("email"), "ORG admin keeps the full user");
        assertTrue(users(Persona.BRANCH_MGR).get(0).hasNonNull("email"), "USER_MANAGEMENT:VIEW keeps the full user");
    }

    // ---- D15 allow-list ---------------------------------------------------------------------------

    private int createCompany(String zone) throws Exception {
        return send(Persona.ORG_ADMIN, post("/tenant/companies"), Map.of("organizationId", fixture.org('A'),
                "name", "IT TZ Company", "code", "IT-TZ-" + fixture.phone().substring(6), "timezone", zone)).getStatus();
    }

    @Test
    void withTheAllowListOnOnlyTheSevenZonesAreAccepted() throws Exception {
        flags.setOverride(FeatureFlag.TENANT_TIMEZONE_ALLOWLIST, fixture.org('A'), true, "it", "TenantAdminIT");
        for (String zone : List.of("Europe/London", "Asia/Karachi", "UTC")) assertEquals(400, createCompany(zone), zone);
        assertEquals(200, createCompany("Asia/Kolkata"));

        Long company = fixture.company(Slot.A1a);
        MockHttpServletResponse r = send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", company), Map.of(
                "name", fixture.state(Company.class, company, Company::getName),
                "code", fixture.state(Company.class, company, Company::getCode), "timezone", "UTC"));
        assertEquals(400, r.getStatus());
        assertEquals("VALIDATION_FAILED", json.readTree(r.getContentAsString()).path("code").asText());
        assertNotEquals("UTC", fixture.state(Company.class, company, Company::getTimezone));

        // A legacy zone that is NOT being changed does not block other edits.
        Long legacy = fixture.freshCompany(Slot.A1a);
        jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", "Europe/London", legacy);
        MockHttpServletResponse kept = send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", legacy), Map.of(
                "name", "IT Legacy Renamed", "code", fixture.state(Company.class, legacy, Company::getCode), "timezone", "Europe/London"));
        assertEquals(200, kept.getStatus(), kept.getContentAsString());
    }

    @Test
    void withTheAllowListOffAnyZoneIsAcceptedAsBefore() throws Exception {
        assertEquals(200, createCompany("Europe/London"));
    }

    // ---- roles: data scope ------------------------------------------------------------------------

    @Test
    void theRoleEditorCanSetAndChangeTheDataScope() throws Exception {
        MockHttpServletResponse created = send(Persona.ORG_ADMIN, post("/tenant/roles"), Map.of("organizationId", fixture.org('A'),
                "roleName", "IT Own Agents " + fixture.phone().substring(6), "roleLevel", "BRANCH", "dataScope", "OWN",
                "permissions", List.of(Map.of("moduleName", "LEADS", "canView", true))));
        assertEquals(200, created.getStatus(), created.getContentAsString());
        Long id = json.readTree(created.getContentAsString()).path("id").asLong();
        assertEquals("OWN", fixture.state(Role.class, id, Role::getDataScope));

        Long fresh = fixture.freshRole(Slot.A1a);
        assertEquals("LEVEL", fixture.state(Role.class, fresh, Role::getDataScope), "a role without a scope is LEVEL");
        MockHttpServletResponse updated = send(Persona.ORG_ADMIN, put("/tenant/roles/{id}", fresh), Map.of(
                "roleName", "IT Now Own", "roleLevel", "BRANCH", "dataScope", "OWN",
                "permissions", List.of(Map.of("moduleName", "LEADS", "canView", true))));
        assertEquals(200, updated.getStatus(), updated.getContentAsString());
        assertEquals(DataScope.OWN.name(), fixture.state(Role.class, fresh, Role::getDataScope));
    }

    // ---- users: current scope, not only the requested one ---------------------------------------

    @Test
    void aCompanyManagerCannotPullAUserOutOfAnotherCompany() throws Exception {
        Long user = fixture.freshUser(Slot.A2a);
        String before = fixture.state(User.class, user, u -> u.getFirstName() + "|" + u.getAllowedCompanies().size());
        MockHttpServletResponse r = send(Persona.COMPANY_MGR, put("/tenant/users/{id}", user), Map.of(
                "roleId", fixture.role('A', Persona.AGENT_OWN_1), "firstName", "Stolen",
                "email", fixture.state(User.class, user, User::getEmail),
                "allowedCompanyIds", List.of(fixture.company(Slot.A1a)), "allowedBranchIds", List.of(fixture.branch(Slot.A1a))));
        assertEquals(403, r.getStatus(), r.getContentAsString());
        assertEquals(before, fixture.state(User.class, user, u -> u.getFirstName() + "|" + u.getAllowedCompanies().size()));
    }

    // ---- logos are validated when saved (module 08 P1 follow-up) ----------------------------------

    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Test
    void anInvalidLogoIsRefusedWhenSavedButAnUnchangedOldOneDoesNotBlockEdits() throws Exception {
        Long company = fixture.freshCompany(Slot.A1a);
        String code = fixture.state(Company.class, company, Company::getCode);
        java.util.function.Function<Object, java.util.Map<String, Object>> body = logo -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>(Map.of("name", "IT Logo Co", "code", code));
            m.put("logoUrl", logo);
            return m;
        };
        MockHttpServletResponse bad = send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", company), body.apply("https://evil.example/logo.png"));
        assertEquals(400, bad.getStatus(), bad.getContentAsString());
        assertEquals("VALIDATION_FAILED", json.readTree(bad.getContentAsString()).path("code").asText());
        assertEquals("null", fixture.state(Company.class, company, Company::getLogoUrl), "nothing saved");

        assertEquals(200, send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", company), body.apply(PNG)).getStatus(), "a PNG data URI is fine");
        assertEquals(200, send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", company), body.apply(null)).getStatus(), "no logo is fine");

        // A legacy invalid logo saved before this check: sending it back unchanged still allows the edit.
        jdbc.update("UPDATE companies SET logo_url = 'http://old.example/logo.png' WHERE id = ?", company);
        assertEquals(200, send(Persona.ORG_ADMIN, put("/tenant/companies/{id}", company), body.apply("http://old.example/logo.png")).getStatus());
    }

    // ---- organizations: name not unique, slug unique ---------------------------------------------

    @Test
    void twoOrganizationsMayShareANameButNotASlug() {
        String n = fixture.phone().substring(6);
        Organization a = orgs.save(Organization.builder().name("IT Same Name").code("IT-SN-A" + n).build());
        Organization b = orgs.save(Organization.builder().name("IT Same Name").code("IT-SN-B" + n).build());
        assertNotEquals(a.getSlug(), b.getSlug());
        assertEquals(OrganizationSlugs.slugify("IT-SN-A" + n), a.getSlug());
        assertTrue(orgs.existsBySlug(a.getSlug()));
    }
}
