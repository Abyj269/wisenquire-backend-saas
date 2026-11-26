package com.leads.it;

import com.leads.exception.WhatsAppApiException;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Slot;
import com.leads.service.BroadcastService;
import com.leads.service.BroadcastServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Module 16 P1 (S3-a, P-8; CLAUDE.md hard rule 2): with flag broadcast.chunked_tx ON the broadcast is
 * sent in chunks with no DB transaction during any Meta call, a stopped run is resumed by the recovery
 * with one wamid per recipient, and one failing chunk marks only its own recipients. Flag OFF keeps the
 * original single-transaction path.
 */
class BroadcastChunkedSendIT extends AbstractIntegrationTest {

    /** Thrown by the mocked Meta client to stop the sender mid-run, like a killed pod (not caught as a send error). */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() { super("simulated pod kill"); }
    }

    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastServiceImpl impl;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("broadcastExecutor") ThreadPoolTaskExecutor executor;

    /**
     * Each chunked send links a chat per recipient; remove them so the shared fixture branch keeps a
     * small inbox (other ITs list the first page of A1a's conversations).
     */
    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.BROADCAST_CHUNKED_TX, fixture.org('A'));
        jdbc.update("DELETE m FROM whatsapp_messages m JOIN whatsapp_conversations c ON c.id = m.conversation_id WHERE c.customer_name LIKE 'IT Recipient %'");
        jdbc.update("DELETE FROM whatsapp_conversations WHERE customer_name LIKE 'IT Recipient %'");
    }

    private void chunkedOn() {
        flags.setOverride(FeatureFlag.BROADCAST_CHUNKED_TX, fixture.org('A'), true, "it", "BroadcastChunkedSendIT");
    }

    /** A SENDING broadcast on A1a with {@code n} PENDING recipients; returns its id. */
    private Long sendingBroadcast(int n) {
        Long id = fixture.freshBroadcast(Slot.A1a);
        jdbc.update("UPDATE broadcasts SET status = 'SENDING', total_recipients = ? WHERE id = ?", n, id);
        String base = fixture.phone().substring(1, 8);
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO broadcast_recipients (broadcast_id, recipient_phone, recipient_name, status) VALUES (?, ?, ?, 'PENDING')",
                    id, "9715" + base + String.format("%03d", i), "IT Recipient " + i);
        }
        return id;
    }

    private String status(Long id) { return jdbc.queryForObject("SELECT status FROM broadcasts WHERE id = ?", String.class, id); }

    private int count(Long id, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM broadcast_recipients WHERE broadcast_id = ? AND " + where, Integer.class, id);
    }

    private void awaitCompleted(Long id) throws InterruptedException {
        for (int i = 0; i < 300 && !"COMPLETED".equals(status(id)); i++) Thread.sleep(100);
        for (int i = 0; i < 100 && (executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()); i++) Thread.sleep(100);
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void noTransactionIsOpenDuringAnyMetaCallAndEveryRecipientIsSentOnce() {
        chunkedOn();
        Long id = sendingBroadcast(120); // 3 chunks of 50
        Set<Boolean> txDuring = ConcurrentHashMap.newKeySet();
        Map<String, Integer> perPhone = new ConcurrentHashMap<>();
        when(graphApi.listTemplates(any())).thenAnswer(inv -> { txDuring.add(TransactionSynchronizationManager.isActualTransactionActive()); return List.of(); });
        when(graphApi.sendTemplateMessage(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            txDuring.add(TransactionSynchronizationManager.isActualTransactionActive());
            perPhone.merge(inv.getArgument(1), 1, Integer::sum);
            return "wamid.it-" + UUID.randomUUID();
        });

        impl.executeBroadcastSend(id);

        assertEquals(Set.of(false), txDuring, "no transaction (and no row lock) may be open during a Meta call");
        assertEquals("COMPLETED", status(id));
        assertEquals(120, count(id, "status = 'SENT' AND wamid IS NOT NULL"));
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(DISTINCT wamid) FROM broadcast_recipients WHERE broadcast_id = ?", Integer.class, id));
        assertEquals(120, perPhone.size());
        assertTrue(perPhone.values().stream().allMatch(c -> c == 1), "one Meta send per recipient");
        assertEquals(120, jdbc.queryForObject("SELECT sent_count FROM broadcasts WHERE id = ?", Integer.class, id));
        assertNotNull(jdbc.queryForObject("SELECT last_heartbeat_at FROM broadcasts WHERE id = ?", java.sql.Timestamp.class, id), "heartbeat per chunk");
    }

    @Test
    void aStoppedRunIsResumedByTheRecoveryWithOneWamidPerRecipient() throws Exception {
        chunkedOn();
        Long id = sendingBroadcast(120);
        AtomicInteger calls = new AtomicInteger();
        Map<String, Integer> accepted = new ConcurrentHashMap<>();
        when(graphApi.listTemplates(any())).thenReturn(List.of());
        when(graphApi.sendTemplateMessage(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 61) throw new SimulatedCrash(); // in the middle of chunk 2
            accepted.merge(inv.getArgument(1), 1, Integer::sum);
            return "wamid.it-" + UUID.randomUUID();
        });

        assertThrows(SimulatedCrash.class, () -> impl.executeBroadcastSend(id));
        assertEquals("SENDING", status(id), "the stopped broadcast is left SENDING");
        assertEquals(60, count(id, "status = 'SENT'"));
        assertTrue(count(id, "status = 'SENDING_ITEM' AND wamid IS NULL") > 0, "the claimed rest of chunk 2 was never sent");

        assertFalse(broadcasts.resumeStalled(id), "a fresh heartbeat is not stalled");
        jdbc.update("UPDATE broadcasts SET last_heartbeat_at = last_heartbeat_at - INTERVAL 10 MINUTE WHERE id = ?", id);
        assertTrue(broadcasts.resumeStalled(id), "no heartbeat for 10 minutes: resumed");
        awaitCompleted(id);

        assertEquals(120, count(id, "status = 'SENT' AND wamid IS NOT NULL"));
        assertEquals(120, jdbc.queryForObject("SELECT COUNT(DISTINCT wamid) FROM broadcast_recipients WHERE broadcast_id = ?", Integer.class, id));
        assertEquals(120, accepted.size());
        assertTrue(accepted.values().stream().allMatch(c -> c == 1), "zero double-sends: " + accepted);
        assertEquals(120, jdbc.queryForObject("SELECT sent_count FROM broadcasts WHERE id = ?", Integer.class, id));
    }

    @Test
    void aMetaErrorOnOneChunkMarksOnlyThatChunk() {
        chunkedOn();
        Long id = sendingBroadcast(150);
        AtomicInteger calls = new AtomicInteger();
        when(graphApi.listTemplates(any())).thenReturn(List.of());
        when(graphApi.sendTemplateMessage(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n > 50 && n <= 100) throw new WhatsAppApiException("Meta returned 500");
            return "wamid.it-" + UUID.randomUUID();
        });

        impl.executeBroadcastSend(id);

        assertEquals("COMPLETED", status(id));
        assertEquals(100, count(id, "status = 'SENT'"));
        assertEquals(50, count(id, "status = 'FAILED' AND error_message = 'Meta returned 500'"));
        assertEquals(50, jdbc.queryForObject("SELECT failed_count FROM broadcasts WHERE id = ?", Integer.class, id));
    }

    @Test
    void withTheFlagOffTheOriginalPathRunsAndIsNeverResumed() {
        Long id = sendingBroadcast(3);
        when(graphApi.listTemplates(any())).thenReturn(List.of());
        when(graphApi.sendTemplateMessage(any(), anyString(), anyString(), anyString(), any())).thenReturn("wamid.it-off-1", "wamid.it-off-2", "wamid.it-off-3");

        impl.executeBroadcastSend(id);

        assertEquals("COMPLETED", status(id));
        assertEquals(3, count(id, "status = 'SENT'"));
        assertNull(jdbc.queryForObject("SELECT last_heartbeat_at FROM broadcasts WHERE id = ?", java.sql.Timestamp.class, id), "old path: no heartbeat");

        Long stuck = sendingBroadcast(2); // an old-path run that died: no heartbeat
        chunkedOn();
        assertFalse(broadcasts.resumeStalled(stuck), "never resumed: its results were rolled back, so resuming would send twice");
    }
}
