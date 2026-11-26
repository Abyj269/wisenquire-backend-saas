package com.leads.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Module 11 P1 (CLAUDE.md hard rule 2): "test connection" calls Meta with no DB transaction open. */
class WhatsAppConfigTestConnectionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theMetaCallRunsOutsideAnyTransaction() throws Exception {
        AtomicReference<Boolean> txDuringCall = new AtomicReference<>();
        when(graphApi.getPhoneNumberInfo(any())).thenAnswer(inv -> {
            txDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return JsonNodeFactory.instance.objectNode().put("verified_name", "IT Verified").put("display_phone_number", "+971 4 000 0000");
        });

        MockHttpServletResponse r = mvc.perform(post("/whatsapp/configs/{id}/test", fixture.config(Slot.A1a))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();

        assertEquals(200, r.getStatus(), r.getContentAsString());
        var body = json.readTree(r.getContentAsString());
        assertTrue(body.path("success").asBoolean());
        assertEquals("IT Verified", body.path("verifiedName").asText());
        assertEquals(Boolean.FALSE, txDuringCall.get(), "no transaction may be open during the Meta HTTP call");
    }

    @Test
    void anotherOrgsConnectionIsStillRefusedBeforeAnyMetaCall() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        when(graphApi.getPhoneNumberInfo(any())).thenAnswer(inv -> { called.set(true); return JsonNodeFactory.instance.objectNode(); });
        int status = mvc.perform(post("/whatsapp/configs/{id}/test", fixture.config(Slot.B1a))
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse().getStatus();
        assertEquals(403, status);
        assertFalse(called.get(), "the scope check runs first");
    }
}
