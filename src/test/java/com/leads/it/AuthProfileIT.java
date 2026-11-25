package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.it.fixture.Persona;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Module 02 P1: /auth/profile is always the CALLER's own identity: user id, organization and data
 * scope come from the verified token's user, for every persona of both orgs (it takes no ids, so
 * there is nothing to point at another tenant).
 */
class AuthProfileIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void theProfileIsTheCallersOwnForEveryPersonaOfBothOrgs() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (char org : new char[]{'A', 'B'}) {
            for (Persona p : Persona.values()) {
                String body = mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + fixture.token(org, p)))
                        .andReturn().getResponse().getContentAsString();
                JsonNode profile = json.readTree(body);
                assertEquals(fixture.user(org, p).longValue(), profile.path("id").asLong(), org + ":" + p + " id");
                assertEquals(fixture.org(org).longValue(), profile.path("organizationId").asLong(), org + ":" + p + " org");
                String expectedScope = (p == Persona.AGENT_OWN_1 || p == Persona.AGENT_OWN_2) ? "OWN" : "LEVEL";
                assertEquals(expectedScope, profile.path("dataScope").asText(), org + ":" + p + " dataScope");
            }
        }
    }
}
