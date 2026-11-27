package com.leads.it;

import com.leads.dto.ai.AiChatResponse;
import com.leads.exception.AiProviderApiException;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 18 P1 (S3-b, P-3b; CLAUDE.md hard rule 2). The in-repo stand-in for the L7 AI scenario: with flag
 * ai.short_transactions ON, 10 AI calls in one branch against a provider that takes 1 s finish in about 1 s
 * (no counter lock is held during the provider call); with it OFF they still run one after another. A failed
 * provider call gives its reservation back; the cap still answers 429 AI_USAGE_LIMIT.
 */
class AiShortTransactionsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.AI_SHORT_TRANSACTIONS, fixture.org('A'));
        jdbc.update("DELETE FROM ai_branch_settings WHERE branch_id = ?", fixture.branch(Slot.A1b));
    }

    private void shortOn() { flags.setOverride(FeatureFlag.AI_SHORT_TRANSACTIONS, fixture.org('A'), true, "it", "AiShortTransactionsIT"); }

    private List<Long> messages(Slot s, int n) {
        fixture.ensureAiProvider(s);
        Long conversation = fixture.freshConversationFor(s, null, null);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(fixture.freshInboundMessage(conversation, "TEXT", "hola " + i));
        return ids;
    }

    private MockHttpServletResponse translate(Long messageId) throws Exception {
        return mvc.perform(post("/ai/messages/{id}/translation", messageId)
                .header("Authorization", "Bearer " + fixture.token('A', Persona.ORG_ADMIN))).andReturn().getResponse();
    }

    private long requestsToday(Slot s) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(request_count),0) FROM ai_usage_counters WHERE branch_id = ? AND period_type = 'DAY'", Long.class, fixture.branch(s));
    }

    /** Runs the translations in parallel; returns the elapsed milliseconds. */
    private long inParallel(List<Long> ids, Set<Boolean> txDuringCall) throws Exception {
        when(aiCalls.chat(anyString(), any(), any(), any())).thenAnswer(inv -> {
            txDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            Thread.sleep(1000);
            return AiChatResponse.builder().content("hello").promptTokens(10).completionTokens(5).build();
        });
        ExecutorService pool = Executors.newFixedThreadPool(ids.size());
        try {
            long start = System.nanoTime();
            List<Future<MockHttpServletResponse>> results = new ArrayList<>();
            for (Long id : ids) results.add(pool.submit(() -> translate(id)));
            for (Future<MockHttpServletResponse> f : results) {
                MockHttpServletResponse r = f.get(60, TimeUnit.SECONDS);
                assertEquals(200, r.getStatus(), r.getContentAsString());
            }
            return (System.nanoTime() - start) / 1_000_000;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tenParallelCallsInOneBranchAreNotSerialised() throws Exception {
        shortOn();
        List<Long> ids = messages(Slot.A1a, 10);
        long before = requestsToday(Slot.A1a);
        Set<Boolean> tx = ConcurrentHashMap.newKeySet();

        long ms = inParallel(ids, tx);

        assertTrue(ms < 5000, "10 calls of 1 s each took " + ms + " ms: they must overlap (serialised would be about 10 s)");
        assertEquals(Set.of(false), tx, "no transaction (and no counter lock) may be open during the provider call");
        assertEquals(before + 10, requestsToday(Slot.A1a), "each call was still admitted and counted");
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_message_translations WHERE message_id IN ("
                + String.join(",", ids.stream().map(String::valueOf).toList()) + ")", Integer.class));
    }

    @Test
    void withTheFlagOffTheCallsStillRunOneAfterAnother() throws Exception {
        List<Long> ids = messages(Slot.A1a, 3);
        Set<Boolean> tx = ConcurrentHashMap.newKeySet();

        long ms = inParallel(ids, tx);

        assertTrue(ms >= 2500, "the original path holds the counter lock during the call, so 3 calls take about 3 s; took " + ms);
        assertEquals(Set.of(true), tx, "the original path runs the call inside its transaction");
    }

    @Test
    void aFailedProviderCallGivesItsReservationBack() throws Exception {
        shortOn();
        Long id = messages(Slot.A1a, 1).get(0);
        long before = requestsToday(Slot.A1a);
        when(aiCalls.chat(anyString(), any(), any(), any())).thenThrow(new AiProviderApiException("provider down"));

        MockHttpServletResponse r = translate(id);

        assertTrue(r.getStatus() >= 400, "the error reaches the caller: " + r.getStatus());
        assertEquals(before, requestsToday(Slot.A1a), "a failed call does not count against the cap");
    }

    @Test
    void summaryScoreAndTranscriptionRunWithNoTransactionDuringTheCall() throws Exception {
        shortOn();
        fixture.ensureAiProvider(Slot.A1a);
        Set<Boolean> tx = ConcurrentHashMap.newKeySet();
        when(aiCalls.chat(anyString(), any(), any(), any())).thenAnswer(inv -> {
            tx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return AiChatResponse.builder().content("SUMMARIZATION".equals(inv.getArgument(0))
                    ? "The customer wants a quote."
                    : "{\"score\":72,\"strengthLabel\":\"HIGH\",\"urgencyLabel\":\"MEDIUM\",\"rationale\":\"Asked for price\"}")
                    .promptTokens(20).completionTokens(10).build();
        });
        when(aiCalls.transcribe(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            tx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return "hello from a voice note";
        });
        when(graphApi.downloadMedia(any(), anyString(), any())).thenAnswer(inv -> {
            tx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new byte[]{1, 2, 3};
        });
        String token = "Bearer " + fixture.token('A', Persona.ORG_ADMIN);

        Long lead = fixture.freshLeadAssignedTo(Slot.A1a, null);
        Long conversation = fixture.freshConversationFor(Slot.A1a, null, lead);
        fixture.freshInboundMessage(conversation, "TEXT", "How much is the 2-seater?");
        MockHttpServletResponse summary = mvc.perform(post("/ai/conversations/{id}/summarize", conversation).header("Authorization", token)).andReturn().getResponse();
        assertEquals(200, summary.getStatus(), summary.getContentAsString());
        assertEquals("The customer wants a quote.", jdbc.queryForObject(
                "SELECT summary_text FROM whatsapp_conversation_summaries WHERE conversation_id = ?", String.class, conversation));

        String leadNumber = jdbc.queryForObject("SELECT lead_number FROM leads WHERE id = ?", String.class, lead);
        MockHttpServletResponse score = mvc.perform(post("/ai/leads/{n}/conversion-score/refresh", leadNumber).header("Authorization", token)).andReturn().getResponse();
        assertEquals(200, score.getStatus(), score.getContentAsString());
        assertEquals(72, jdbc.queryForObject("SELECT score FROM lead_conversion_scores WHERE lead_id = ?", Integer.class, lead));

        Long audio = fixture.freshInboundMessage(conversation, "AUDIO", null);
        jdbc.update("UPDATE whatsapp_messages SET media_id = ?, media_mime_type = 'audio/ogg' WHERE id = ?", "it-media-" + audio, audio);
        MockHttpServletResponse transcript = mvc.perform(post("/ai/messages/{id}/transcription", audio).header("Authorization", token)).andReturn().getResponse();
        assertEquals(200, transcript.getStatus(), transcript.getContentAsString());
        assertEquals("hello from a voice note", jdbc.queryForObject(
                "SELECT transcript_text FROM whatsapp_message_transcriptions WHERE message_id = ?", String.class, audio));

        assertEquals(Set.of(false), tx, "no transaction during the summary, score, media download or transcription call");
    }

    @Test
    void theCapStillAnswers429() throws Exception {
        shortOn();
        Long id = messages(Slot.A1b, 1).get(0);
        long used = requestsToday(Slot.A1b);
        jdbc.update("INSERT INTO ai_branch_settings (branch_id, company_id, daily_request_cap, monthly_request_cap, monthly_token_cap, "
                + "context_max_messages, context_max_lookback_hours, context_max_chars, notify_threshold_percent, reply_suggestion_enabled, "
                + "message_improvement_enabled, translation_enabled, conversion_scoring_enabled, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, 3000, 5000000, 20, 72, 8000, 80, 1, 1, 1, 1, 0, NOW(), NOW())",
                fixture.branch(Slot.A1b), fixture.company(Slot.A1b), (int) used);
        when(aiCalls.chat(anyString(), any(), any(), any())).thenReturn(AiChatResponse.builder().content("x").build());

        MockHttpServletResponse r = translate(id);

        assertEquals(429, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("AI_USAGE_LIMIT"), r.getContentAsString());
        assertEquals(used, requestsToday(Slot.A1b), "a refused call is not counted (its reservation rolled back)");
    }
}
