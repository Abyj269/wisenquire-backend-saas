package com.leads.it.matrix;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.AbstractIntegrationTest;
import com.leads.it.fixture.Persona;
import com.leads.it.matrix.EndpointSpec.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * D12 / F-9 with flag {@code errors.uniform_not_found} ON for org A: every cross-scope denial row of
 * the matrix becomes 404 NOT_FOUND, and a missing record is 404 too, so the response no longer
 * tells whether a record exists. Two kinds of row stay 403 FORBIDDEN by design:
 * <ul>
 *   <li>X_COMPANY_WIDE: a branch-level user asking for its own company's company-wide data is a
 *       permission refusal (TenantAccess.companyWide), not a hidden record;</li>
 *   <li>POST /broadcasts/{id}/send: denied by the BROADCASTS:SEND permission check (module 16 finding).</li>
 * </ul>
 * The flag is switched back off afterwards, so the other ITs (shared context) see the default.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UniformNotFoundMatrixIT extends AbstractIntegrationTest {

    private static final Set<RowType> CROSS_SCOPE = Set.of(RowType.X_ORG, RowType.X_COMPANY, RowType.X_BRANCH, RowType.X_COMPANY_WIDE);

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;

    @BeforeAll
    void flagOn() {
        flags.setOverride(FeatureFlag.ERRORS_UNIFORM_NOT_FOUND, fixture.org('A'), true, "it-test", "UniformNotFoundMatrixIT");
        Matrix.flagsOn(flags, fixture);
    }

    @AfterAll
    void flagOff() {
        flags.clearOverride(FeatureFlag.ERRORS_UNIFORM_NOT_FOUND, fixture.org('A'));
        Matrix.flagsOff(flags, fixture);
    }

    static Stream<Row> rows() {
        return Matrix.rows().stream().filter(r -> CROSS_SCOPE.contains(r.type()) && r.knownLeak() == null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void crossScopeIsNotFound(Row row) throws Exception {
        boolean permissionDenial = row.type() == RowType.X_COMPANY_WIDE || row.spec().key.equals("POST /broadcasts/{id}/send");
        int expected = permissionDenial ? 403 : 404;
        String expectedCode = permissionDenial ? "FORBIDDEN" : "NOT_FOUND";
        MvcResult result = perform(row.request().build(fixture, row.target(), new EndpointSpec.Probe()), row.org(), row.persona());
        assertEquals(expected, result.getResponse().getStatus(), row + " -> " + result.getResponse().getContentAsString());
        assertEquals(expectedCode, json(result).get("code").asText(), row.toString());
    }

    @Test
    void missingRecordIsTheSame404AsAForeignOne() throws Exception {
        MvcResult missing = perform(get("/leads/{id}", Long.MAX_VALUE), 'A', Persona.ORG_ADMIN);
        MvcResult foreign = perform(get("/leads/{id}", fixture.lead(com.leads.it.fixture.Slot.B1a)), 'A', Persona.ORG_ADMIN);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(404, foreign.getResponse().getStatus());
        // Same code and message: nothing tells the two apart.
        assertEquals(json(missing).get("code"), json(foreign).get("code"));
        assertEquals(json(missing).get("message"), json(foreign).get("message"));
    }

    @Test
    void anotherOrgKeepsTheOldStatuses() throws Exception {
        // Org B has no override: a missing record is still 400 and a foreign one 403.
        assertEquals(400, perform(get("/leads/{id}", Long.MAX_VALUE), 'B', Persona.ORG_ADMIN).getResponse().getStatus());
        assertEquals(403, perform(get("/leads/{id}", fixture.lead(com.leads.it.fixture.Slot.A1a)), 'B', Persona.ORG_ADMIN).getResponse().getStatus());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, char org, Persona persona) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + fixture.token(org, persona))).andReturn();
    }

    private static com.fasterxml.jackson.databind.JsonNode json(MvcResult r) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getContentAsString());
    }
}
