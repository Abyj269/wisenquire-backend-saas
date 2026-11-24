package com.leads.it;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 01 §7 "Memo": one identity query per request on GET /leads/{id}. Before Part B item 1 this was
 * 2 in the JWT filter plus about 3 in TenantAccess.record(Lead). Uses Hibernate statistics
 * (enabled in application-it.yml) and counts every executed query on User by email.
 */
class ActorMemoIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory emf;

    @Test
    void oneIdentityQueryPerRequest() throws Exception {
        fixture.ensureBuilt();
        String token = fixture.token('A', Persona.BRANCH_MGR);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/leads/{id}", fixture.lead(Slot.A1a)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertEquals(1, identityQueries(stats), "identity (User by email) queries for one GET /leads/{id}");
    }

    @Test
    void stillOneQueryOnAWritePath() throws Exception {
        fixture.ensureBuilt();
        Long lead = fixture.freshLead(Slot.A1a);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(post("/leads/{id}/notes", lead).header("Authorization", "Bearer " + fixture.token('A', Persona.AGENT_OWN_1))
                .contentType("application/json").content("{\"note\":\"memo write path\"}")).andExpect(status().is2xxSuccessful());
        assertEquals(1, identityQueries(stats), "the memoised (detached) actor also works when a write links it to a new row");
    }

    private static long identityQueries(Statistics stats) {
        long n = 0;
        for (String q : stats.getQueries()) {
            String lower = q.toLowerCase();
            if (lower.contains("user") && lower.contains("email")) n += stats.getQueryStatistics(q).getExecutionCount();
        }
        return n;
    }
}
