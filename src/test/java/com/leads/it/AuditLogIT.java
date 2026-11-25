package com.leads.it;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 01 Part B item 9: writes on @Audited controllers land in audit_log; reads do not; refusals are recorded. */
class AuditLogIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private List<Map<String, Object>> rowsWithTrace(String trace) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id = ?", trace);
    }

    private int create(char org, Long companyId, String trace) throws Exception {
        return mvc.perform(post("/settings/saved-replies").param("companyId", String.valueOf(companyId))
                        .header("Authorization", "Bearer " + fixture.token(org, Persona.ORG_ADMIN))
                        .header("X-Request-Id", trace).contentType("application/json")
                        .content("{\"title\":\"IT audit\",\"body\":\"Hello\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aSuccessfulWriteIsRecordedWithWhoWhatAndWhere() throws Exception {
        assertEquals(200, create('A', fixture.company(Slot.A1a), "audit-ok-0001"));
        List<Map<String, Object>> rows = rowsWithTrace("audit-ok-0001");
        assertEquals(1, rows.size());
        Map<String, Object> r = rows.get(0);
        assertEquals("SUCCESS", r.get("outcome"));
        assertEquals("POST /settings/saved-replies", r.get("action"));
        assertEquals("saved-replies", r.get("entity_type"));
        assertEquals(fixture.org('A'), ((Number) r.get("organization_id")).longValue());
        assertEquals(fixture.user('A', Persona.ORG_ADMIN), ((Number) r.get("actor_user_id")).longValue());
        assertTrue(String.valueOf(r.get("after_json")).contains("IT audit"));
    }

    @Test
    void aRefusedWriteIsRecordedAsDenied() throws Exception {
        int status = create('A', fixture.company(Slot.B1a), "audit-denied-01");
        assertEquals(403, status);
        List<Map<String, Object>> rows = rowsWithTrace("audit-denied-01");
        assertEquals(1, rows.size());
        assertEquals("DENIED", rows.get(0).get("outcome"));
        assertEquals(fixture.org('A'), ((Number) rows.get(0).get("organization_id")).longValue(), "the caller's org, never the body's");
    }

    @Test
    void readsAreNotAudited() throws Exception {
        mvc.perform(get("/settings/saved-replies").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN)).header("X-Request-Id", "audit-read-0001"));
        assertTrue(rowsWithTrace("audit-read-0001").isEmpty());
    }
}
