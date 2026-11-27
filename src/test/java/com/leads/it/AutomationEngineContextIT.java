package com.leads.it;

import com.leads.it.fixture.Slot;
import com.leads.service.AutomationEngineService;
import com.leads.service.TenantAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Module 17 P1 (F-3): an automation run handed off from the webhook carries its tenant: the log context
 * has orgId and TenantAccess is scoped to the conversation's company. A hand-off refused because the
 * executor's queue is full records a SKIPPED_QUEUE_FULL execution instead of being lost.
 */
class AutomationEngineContextIT extends AbstractIntegrationTest {

    @Autowired AutomationEngineService engine;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> automations = new ArrayList<>();

    @AfterEach
    void pauseOurAutomations() {
        for (Long id : automations) jdbc.update("UPDATE automations SET status = 'PAUSED' WHERE id = ?", id);
    }

    /** An ACTIVE automation on A1a triggered by {@code keyword}, and an inbound message with that text; returns the message id. */
    private Long[] activeAutomationAndInbound(String keyword) {
        Long automation = fixture.freshAutomation(Slot.A1a, false, keyword);
        automations.add(automation);
        jdbc.update("UPDATE automations SET status = 'ACTIVE' WHERE id = ?", automation);
        Long conversation = fixture.freshConversationFor(Slot.A1a, null, null);
        jdbc.update("INSERT INTO whatsapp_messages (conversation_id, direction, message_type, body_text, status, is_auto_reply, is_from_ad, created_at) "
                + "VALUES (?, 'INBOUND', 'TEXT', ?, 'DELIVERED', false, false, NOW())", conversation, keyword);
        Long message = jdbc.queryForObject("SELECT MAX(id) FROM whatsapp_messages WHERE conversation_id = ?", Long.class, conversation);
        return new Long[]{automation, message};
    }

    @Test
    void anAsyncRunHasTheOrgInTheLogContextAndTheCompanyScope() throws Exception {
        String keyword = "it-ctx-" + fixture.phone().substring(1);
        Long[] ids = activeAutomationAndInbound(keyword);
        AtomicReference<String> orgInMdc = new AtomicReference<>();
        AtomicReference<Long> scopedCompany = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        when(graphApi.sendTextMessage(any(), anyString(), anyString())).thenAnswer(inv -> {
            orgInMdc.set(MDC.get("orgId"));
            scopedCompany.set(TenantAccess.systemCompanyId());
            thread.set(Thread.currentThread().getName());
            return "wamid.it-ctx-" + System.nanoTime();
        });

        engine.onIncomingWhatsAppMessage(ids[1]); // @Async: runs on the automation executor
        for (int i = 0; i < 200 && thread.get() == null; i++) Thread.sleep(50);

        assertNotNull(thread.get(), "the automation sent its reply");
        assertTrue(thread.get().startsWith("automation-"), "ran on the automation executor: " + thread.get());
        assertEquals(String.valueOf(fixture.org('A')), orgInMdc.get(), "orgId is in the MDC of the async run");
        assertEquals(fixture.company(Slot.A1a), scopedCompany.get(), "the run is scoped to the conversation's company");
        for (int i = 0; i < 100 && !"COMPLETED".equals(status(ids[0])); i++) Thread.sleep(50);
        assertEquals("COMPLETED", status(ids[0]));
    }

    @Test
    void aFullQueueRecordsASkippedExecutionAndSendsNothing() {
        String keyword = "it-full-" + fixture.phone().substring(1);
        Long[] ids = activeAutomationAndInbound(keyword);

        engine.recordQueueFull(ids[1]);

        assertEquals("SKIPPED_QUEUE_FULL", status(ids[0]));
        assertTrue(jdbc.queryForObject("SELECT failure_reason FROM automation_executions WHERE automation_id = ?", String.class, ids[0])
                .contains("queue was full"));
        verifyNoInteractions(graphApi);
        assertNull(MDC.get("orgId"), "the caller's log context is restored");
        assertNull(TenantAccess.systemCompanyId(), "the caller's scope is restored");
    }

    private String status(Long automation) {
        List<String> rows = jdbc.queryForList("SELECT status FROM automation_executions WHERE automation_id = ? ORDER BY id DESC", String.class, automation);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
