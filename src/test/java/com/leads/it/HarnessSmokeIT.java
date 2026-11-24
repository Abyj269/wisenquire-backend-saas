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
    void anonymousDataRequestIs401() throws Exception {
        mvc.perform(get("/leads")).andExpect(status().isUnauthorized());
    }
}
