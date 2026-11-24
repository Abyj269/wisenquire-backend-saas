package com.leads.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The harness itself: MySQL 8 is up, every Flyway migration applied, and the security chain is live. */
class HarnessSmokeIT extends AbstractIntegrationTest {

    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;

    @Test
    void allMigrationsAppliedOnMySql8() {
        var info = flyway.info();
        assertNotNull(info.current(), "no migration applied");
        assertEquals(0, info.pending().length, "pending migrations");
        assertEquals(info.all()[info.all().length - 1].getVersion(), info.current().getVersion());
    }

    @Test
    void openApiPublishesTheD12ErrorContractAndMeEndpoint() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(doc.contains("\"ErrorResponse\""), "ErrorResponse schema");
        org.junit.jupiter.api.Assertions.assertTrue(doc.contains("\"TENANT_SUSPENDED\""), "ErrorCode enum values");
        org.junit.jupiter.api.Assertions.assertTrue(doc.contains("/me/feature-flags"), "GET /me/feature-flags");
    }

    @Test
    void unauthenticatedBodyHasACode() throws Exception {
        mvc.perform(get("/leads")).andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Please log in to continue."));
    }

    @Test
    void anonymousDataRequestIs401() throws Exception {
        mvc.perform(get("/leads")).andExpect(status().isUnauthorized());
    }
}
