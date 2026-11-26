package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 12 P1 part 2 (S3-d, CLAUDE.md hard rule 2): an agent reply calls Graph with no DB transaction
 * (and so no row lock) open; the PENDING reservation is replaced by the result; a repeated
 * clientMessageId sends once.
 */
class WhatsAppSendWithoutTransactionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private MockHttpServletResponse send(Long conversation, String clientId) throws Exception {
        return mvc.perform(post("/whatsapp/messages/send").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", conversation, "message", "IT reply", "clientMessageId", clientId)))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();
    }

    @Test
    void theGraphCallRunsOutsideAnyTransactionAndTheResultIsRecorded() throws Exception {
        Long conversation = fixture.freshConversationFor(Slot.A1a, null, null);
        jdbc.update("UPDATE whatsapp_conversations SET last_customer_message_epoch = ? WHERE id = ?", java.time.Instant.now().getEpochSecond() - 60, conversation);
        AtomicReference<Boolean> txDuringCall = new AtomicReference<>();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        when(graphApi.sendTextMessage(any(), anyString(), anyString())).thenAnswer(inv -> {
            txDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            calls.set(calls.get() + 1);
            return "wamid.it-" + System.nanoTime();
        });

        String clientId = "it-s3d-" + fixture.phone();
        MockHttpServletResponse r = send(conversation, clientId);
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertEquals("ACCEPTED", json.readTree(r.getContentAsString()).path("status").asText());
        assertEquals(Boolean.FALSE, txDuringCall.get(), "no transaction (and no row lock) may be open during the Graph call");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages WHERE conversation_id = ? AND status = 'PENDING'", Integer.class, conversation),
                "the reservation is replaced by the result");

        assertEquals(200, send(conversation, clientId).getStatus());
        assertEquals(1, calls.get(), "a repeated clientMessageId is not sent again");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages WHERE conversation_id = ? AND client_message_id = ?", Integer.class, conversation, clientId));
    }
}
