package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 13 P1: no DB transaction (and so no row lock) is open during any template Graph call
 * (CLAUDE.md hard rule 2), and the usage range is the number's company's local days (D15).
 */
class WhatsAppTemplatesWithoutTransactionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private String savedZone;

    @AfterEach
    void restoreZone() {
        if (savedZone != null) jdbc.update("UPDATE companies SET timezone = ? WHERE id = ?", savedZone, fixture.company(Slot.A2a));
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();
    }

    @Test
    void noTransactionIsOpenDuringAnyTemplateGraphCall() throws Exception {
        Map<String, Boolean> txDuring = new HashMap<>();
        ObjectNode template = json.createObjectNode().put("id", "it-tpl-9").put("name", "it_tx_template").put("language", "en_US");
        template.putArray("components");
        Answer<Object> record = inv -> {
            txDuring.merge(inv.getMethod().getName(), TransactionSynchronizationManager.isActualTransactionActive(), Boolean::logicalOr);
            return switch (inv.getMethod().getName()) {
                case "listTemplates" -> List.of(template);
                case "requireTemplate" -> template;
                case "createTemplate", "updateTemplate" -> json.createObjectNode().put("id", "it-tpl-9");
                case "createUploadSession" -> "it-session";
                case "uploadSessionFile" -> "it-handle";
                default -> null;
            };
        };
        when(graphApi.listTemplates(any())).thenAnswer(record);
        when(graphApi.requireTemplate(any(), anyString())).thenAnswer(record);
        when(graphApi.createTemplate(any(), anyMap())).thenAnswer(record);
        when(graphApi.updateTemplate(any(), anyString(), anyMap())).thenAnswer(record);
        when(graphApi.createUploadSession(any(), anyLong(), anyString(), anyString())).thenAnswer(record);
        when(graphApi.uploadSessionFile(any(), anyString(), any())).thenAnswer(record);
        doAnswer(record).when(graphApi).deleteTemplate(any(), anyString());

        String config = String.valueOf(fixture.config(Slot.A1a));
        String body = json.writeValueAsString(Map.of("configId", fixture.config(Slot.A1a), "name", "it_tx_template", "category", "UTILITY",
                "language", "en_US", "bodyText", "Your order is ready."));
        List<MockHttpServletResponse> responses = new ArrayList<>();
        responses.add(call(get("/whatsapp/templates").param("configId", config)));
        responses.add(call(post("/whatsapp/templates").contentType(MediaType.APPLICATION_JSON).content(body)));
        responses.add(call(put("/whatsapp/templates/{id}", "it-tpl-9").contentType(MediaType.APPLICATION_JSON).content(body)));
        responses.add(call(delete("/whatsapp/templates/{name}", "it_tx_template").param("configId", config)));
        responses.add(call(multipart("/whatsapp/templates/media").file(new MockMultipartFile("file", "s.png", "image/png", new byte[]{1, 2}))
                .param("configId", config)));
        for (var r : responses) assertTrue(r.getStatus() >= 200 && r.getStatus() < 300, r.getStatus() + " " + r.getContentAsString());

        assertEquals(java.util.Set.of("listTemplates", "requireTemplate", "createTemplate", "updateTemplate", "deleteTemplate",
                "createUploadSession", "uploadSessionFile"), txDuring.keySet(), "every template Graph call was exercised");
        txDuring.forEach((method, active) -> assertFalse(active, method + " ran inside a DB transaction"));
    }

    @Test
    void usageCountsTheCompanysLocalDays() throws Exception {
        Long company = fixture.company(Slot.A2a);
        savedZone = jdbc.queryForObject("SELECT timezone FROM companies WHERE id = ?", String.class, company);
        jdbc.update("UPDATE companies SET timezone = 'Asia/Kolkata' WHERE id = ?", company);
        Long conversation = fixture.freshConversationFor(Slot.A2a, null, null);
        String early = "it_tz_early_" + fixture.phone().substring(1), late = "it_tz_late_" + fixture.phone().substring(1);
        // Stored times are server wall-clock (Asia/Dubai, IST - 1:30).
        templateMessage(conversation, early, LocalDateTime.of(2026, 3, 10, 23, 0)); // 00:30 IST on the 11th
        templateMessage(conversation, late, LocalDateTime.of(2026, 3, 11, 23, 0));  // 00:30 IST on the 12th

        MockHttpServletResponse r = call(get("/whatsapp/templates/usage").param("configId", String.valueOf(fixture.config(Slot.A2a)))
                .param("from", "2026-03-11").param("to", "2026-03-11"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        List<String> names = new ArrayList<>();
        for (JsonNode row : json.readTree(r.getContentAsString())) names.add(row.path("templateName").asText());
        assertTrue(names.contains(early), "00:30 IST on the 11th is the 11th: " + names);
        assertFalse(names.contains(late), "00:30 IST on the 12th is not the 11th, though it is the 11th in Dubai: " + names);
    }

    private void templateMessage(Long conversation, String template, LocalDateTime serverWallClock) {
        jdbc.update("INSERT INTO whatsapp_messages (conversation_id, direction, message_type, template_name, status, is_auto_reply, is_from_ad, created_at) "
                + "VALUES (?, 'OUTBOUND', 'TEMPLATE', ?, 'SENT', false, false, NOW())", conversation, template);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM whatsapp_messages WHERE conversation_id = ?", Long.class, conversation);
        fixture.setCreatedAt("whatsapp_messages", id, serverWallClock);
    }
}
