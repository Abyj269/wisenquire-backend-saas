package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.ai.AiChatResponse;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.entity.Company;
import com.leads.service.AiUsageLedger;
import com.leads.service.CompanyClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 18 P1 part 2: D9 platform-owned AI rates (tenants cannot edit them, the ledger prices from them), the
 * cap's resetsAt in server time for the company's midnight (D15), and OWN-RECORD on the assistant endpoints (D14).
 */
class AiRatesAndScopeIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiUsageLedger ledger;
    @Autowired CompanyClock clock;
    @Autowired jakarta.persistence.EntityManager em;
    private final ObjectMapper json = new ObjectMapper();
    private String savedZone;

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.AI_SHORT_TRANSACTIONS, fixture.org('A'));
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        jdbc.update("DELETE FROM platform_ai_model_rates WHERE model_name LIKE '%-it'");
        jdbc.update("DELETE FROM ai_model_rates WHERE model_name LIKE '%-it'");
        jdbc.update("DELETE FROM ai_branch_settings WHERE branch_id = ?", fixture.branch(Slot.A2b));
        if (savedZone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", savedZone, fixture.company(Slot.A2b));
    }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    @Test
    void ratesArePlatformOwnedAndTheLedgerPricesFromThem() throws Exception {
        MockHttpServletResponse put = call(Persona.ORG_ADMIN, put("/ai/settings/usage-report/rates").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("companyId", fixture.company(Slot.A1a), "provider", "OPENAI",
                        "model", "gpt-it", "inputRate", 1, "outputRate", 2))));
        assertEquals(403, put.getStatus(), "a tenant admin can no longer edit rates (D9)");

        jdbc.update("INSERT INTO platform_ai_model_rates (provider, model_name, input_per_million, output_per_million) VALUES ('OPENAI','gpt-it',3,6)");
        jdbc.update("INSERT INTO ai_model_rates (company_id, provider, model_name, input_per_million, output_per_million) VALUES (?, 'OPENAI','gpt-it',99,99)",
                fixture.company(Slot.A1a));
        JsonNode rates = json.readTree(call(Persona.BRANCH_MGR, get("/ai/settings/usage-report/rates")
                .param("companyId", String.valueOf(fixture.company(Slot.A1a)))).getContentAsString());
        assertTrue(rates.toString().contains("gpt-it"), "every company sees the platform rates: " + rates);

        AiProviderConfig config = em.find(AiProviderConfig.class, fixture.ensureAiProvider(Slot.A1a));
        AiUsageLedger.Ticket ticket = ledger.start(config, "gpt-it", "TRANSLATION");
        assertEquals(0, new BigDecimal("3").compareTo(ticket.inputRate()), "the platform rate wins over the old company rate");
        assertEquals(0, new BigDecimal("6").compareTo(ticket.outputRate()));

        jdbc.update("INSERT INTO ai_model_rates (company_id, provider, model_name, input_per_million, output_per_million) VALUES (?, 'OPENAI','only-company-it',4,8)",
                fixture.company(Slot.A1a));
        assertEquals(0, new BigDecimal("4").compareTo(ledger.start(config, "only-company-it", "TRANSLATION").inputRate()),
                "a model with no platform rate falls back to the company rate (nothing goes unpriced)");
    }

    @Test
    void theCapsResetTimeIsTheCompanysMidnightInServerTime() throws Exception {
        Long companyId = fixture.company(Slot.A2b);
        savedZone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, companyId);
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", companyId);
        flags.setOverride(FeatureFlag.AI_SHORT_TRANSACTIONS, fixture.org('A'), true, "it", "AiRatesAndScopeIT");
        fixture.ensureAiProvider(Slot.A2b);
        Long conversation = fixture.freshConversationFor(Slot.A2b, null, null);
        Long message = fixture.freshInboundMessage(conversation, "TEXT", "hola");
        jdbc.update("INSERT INTO ai_branch_settings (branch_id, company_id, daily_request_cap) VALUES (?, ?, 0)", fixture.branch(Slot.A2b), companyId);
        when(aiCalls.chat(anyString(), any(), any(), any())).thenReturn(AiChatResponse.builder().content("x").build());

        MockHttpServletResponse r = call(Persona.ORG_ADMIN, post("/ai/messages/{id}/translation", message));

        assertEquals(429, r.getStatus(), r.getContentAsString());
        Company company = em.find(Company.class, companyId);
        LocalDateTime expected = clock.toServer(company, clock.today(company).plusDays(1).atStartOfDay());
        assertEquals(expected, LocalDateTime.parse(json.readTree(r.getContentAsString()).path("details").path("resetsAt").asText()),
                "the next IST midnight, sent as server (Dubai) time so the FE pipe shows 00:00 IST");
    }

    @Test
    void anOwnAgentCannotUseAiOnAnotherAgentsConversation() throws Exception {
        flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "AiRatesAndScopeIT");
        flags.setOverride(FeatureFlag.AI_SHORT_TRANSACTIONS, fixture.org('A'), true, "it", "AiRatesAndScopeIT");
        fixture.ensureAiProvider(Slot.A1a);
        when(aiCalls.chat(anyString(), any(), any(), any())).thenReturn(AiChatResponse.builder().content("ok").promptTokens(1).completionTokens(1).build());
        Long a1 = fixture.user('A', Persona.AGENT_OWN_1), a2 = fixture.user('A', Persona.AGENT_OWN_2);
        Long others = fixture.freshConversationFor(Slot.A1a, a2, null);
        Long othersMessage = fixture.freshInboundMessage(others, "TEXT", "hola");
        Long mine = fixture.freshConversationFor(Slot.A1a, a1, null);
        fixture.freshInboundMessage(mine, "TEXT", "hello");

        assertEquals(403, call(Persona.AGENT_OWN_1, post("/ai/conversations/{id}/summarize", others)).getStatus(), "OWN-RECORD: summarize");
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/ai/messages/{id}/translation", othersMessage)).getStatus(), "OWN-RECORD: translate");
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/ai/conversations/{id}/suggest-reply", others)).getStatus(), "OWN-RECORD: suggest");
        assertEquals(200, call(Persona.AGENT_OWN_1, post("/ai/conversations/{id}/summarize", mine)).getStatus(), "their own conversation works");
    }
}
