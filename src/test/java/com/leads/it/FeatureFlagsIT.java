package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** D13 (01 §7 "Flags"): override beats default, eviction on write, jobs resolve by explicit org, DB = enum. */
class FeatureFlagsIT extends AbstractIntegrationTest {

    private static final FeatureFlag FLAG = FeatureFlag.WA_COST_DISPLAY; // exposed, default off, unused by code yet

    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void reset() {
        for (char o : new char[]{'A', 'B'}) flags.clearOverride(FLAG, fixture.org(o));
        flags.setDefault(FLAG, false);
    }

    @Test
    void databaseRowsMatchTheEnumExactly() {
        Set<String> db = new HashSet<>(jdbc.queryForList("SELECT flag_key FROM feature_flags", String.class));
        Set<String> code = Arrays.stream(FeatureFlag.values()).map(f -> f.key).collect(Collectors.toSet());
        assertEquals(code, db, "V5x seed and FeatureFlag enum differ");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feature_flags WHERE enabled_default = 1", Integer.class),
                "every flag must start OFF (old behaviour)");
    }

    @Test
    void overrideBeatsDefaultAndOnlyForThatOrg() {
        Long a = fixture.org('A'), b = fixture.org('B');
        assertFalse(flags.isOn(FLAG, a));
        flags.setOverride(FLAG, a, true, "it", "test");
        assertTrue(flags.isOn(FLAG, a), "override on");
        assertFalse(flags.isOn(FLAG, b), "other org keeps the default");
        flags.setDefault(FLAG, true);
        flags.setOverride(FLAG, b, false, "it", "test");
        assertFalse(flags.isOn(FLAG, b), "an OFF override beats an ON default");
        assertTrue(flags.isOn(FLAG, null), "no org = global default");
    }

    @Test
    void writesEvictTheCacheImmediately() {
        Long a = fixture.org('A');
        assertFalse(flags.isOn(FLAG, a)); // cached now
        flags.setOverride(FLAG, a, true, "it", "test");
        assertTrue(flags.isOn(FLAG, a), "override visible without waiting for the 60 s TTL");
        flags.clearOverride(FLAG, a);
        assertFalse(flags.isOn(FLAG, a));
        flags.setDefault(FLAG, true);
        assertTrue(flags.isOn(FLAG, a), "default change evicts every org");
    }

    @Test
    void aJobResolvesByExplicitOrgWithoutARequest() {
        // No SecurityContext / TenantContext here, like a scheduled job.
        flags.setOverride(FLAG, fixture.org('B'), true, "it", "test");
        assertTrue(flags.isOn(FLAG, fixture.org('B')));
        assertFalse(flags.isOn(FLAG), "no tenant context = global default");
    }

    @Test
    void meEndpointShowsOnlyTheCallersOrgAndOnlyExposedFlags() throws Exception {
        flags.setOverride(FLAG, fixture.org('B'), true, "it", "test");
        JsonNode a = me('A'), b = me('B');
        assertFalse(a.get(FLAG.key).asBoolean(), "org B's override must not leak to org A");
        assertTrue(b.get(FLAG.key).asBoolean());
        for (FeatureFlag f : FeatureFlag.values()) assertEquals(f.exposed, a.has(f.key), f.key);
    }

    private JsonNode me(char org) throws Exception {
        String body = mvc.perform(get("/me/feature-flags").header("Authorization", "Bearer " + fixture.token(org, Persona.BRANCH_MGR)))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }
}
