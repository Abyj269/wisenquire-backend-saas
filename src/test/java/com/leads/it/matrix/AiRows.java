package com.leads.it.matrix;

import com.leads.dto.ai.AiChatResponse;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;
import com.leads.service.AiMeteredCalls;
import com.leads.service.WhatsAppGraphApiClient;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.PLATFORM;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 18 P1 part 2: the 20 AI endpoints. The assistant endpoints are scoped through the conversation,
 * message or lead (BRANCH, every persona holds AI_ASSISTANT); AI settings are manager-only (AI_SETTINGS),
 * so agents get CFG_SCOPE rows; the platform rates (D9) refuse every tenant persona. The AI provider is
 * mocked ({@link #stub}); no real provider is called.
 */
final class AiRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};

    static List<EndpointSpec> specs() {
        return List.of(
                assistant("POST /ai/conversations/{conversationId}/suggest-reply",
                        (f, s, p) -> post("/ai/conversations/{id}/suggest-reply", chat(f, s))),
                assistant("POST /ai/conversations/{conversationId}/improve-draft",
                        (f, s, p) -> withJson(post("/ai/conversations/{id}/improve-draft", chat(f, s)), json("draftText", "hi, price?"))),
                assistant("POST /ai/conversations/{conversationId}/summarize",
                        (f, s, p) -> post("/ai/conversations/{id}/summarize", chat(f, s))),
                assistant("GET /ai/messages/{messageId}/translation", (f, s, p) -> {
                    Long message = message(f, s, "TEXT");
                    f.freshTranslation(s, message);
                    return get("/ai/messages/{id}/translation", message);
                }),
                assistant("POST /ai/messages/{messageId}/translation",
                        (f, s, p) -> post("/ai/messages/{id}/translation", message(f, s, "TEXT"))),
                assistant("POST /ai/messages/{messageId}/transcription",
                        (f, s, p) -> post("/ai/messages/{id}/transcription", message(f, s, "AUDIO"))),
                assistant("GET /ai/leads/{leadNumber}/conversion-score", (f, s, p) -> {
                    Long lead = scoredLead(f, s);
                    f.freshLeadScore(lead);
                    return get("/ai/leads/{n}/conversion-score", f.leadNumber(lead));
                }),
                assistant("POST /ai/leads/{leadNumber}/conversion-score/refresh",
                        (f, s, p) -> post("/ai/leads/{n}/conversion-score/refresh", f.leadNumber(scoredLead(f, s)))),

                settings(EndpointSpec.of("GET /ai/settings/providers", COMPANY,
                        (f, s, p) -> get("/ai/settings/providers").param("companyId", str(f.company(s))))),
                settings(EndpointSpec.of("GET /ai/settings/providers/branch/{branchId}", BRANCH,
                        (f, s, p) -> get("/ai/settings/providers/branch/{id}", f.branch(s)))),
                settings(EndpointSpec.of("POST /ai/settings/providers", BRANCH, (f, s, p) -> {
                    Long id = f.ensureAiProvider(s, AiProviderType.GEMINI);
                    p.watch(() -> f.state(AiProviderConfig.class, id, c -> c.getModelName()));
                    return withJson(post("/ai/settings/providers"), json("id", id, "branchId", f.branch(s), "provider", "GEMINI", "modelName", "gemini-it-renamed"));
                })),
                settings(EndpointSpec.of("POST /ai/settings/providers/{id}/select", BRANCH,
                        (f, s, p) -> post("/ai/settings/providers/{id}/select", f.ensureAiProvider(s)))),
                settings(EndpointSpec.of("DELETE /ai/settings/providers/{id}", BRANCH, (f, s, p) -> {
                    Long id = f.ensureAiProvider(s, AiProviderType.GROK);
                    p.watch(() -> String.valueOf(f.exists(AiProviderConfig.class, id)));
                    return delete("/ai/settings/providers/{id}", id);
                })),
                settings(EndpointSpec.of("POST /ai/settings/providers/{id}/test", BRANCH,
                        (f, s, p) -> post("/ai/settings/providers/{id}/test", f.ensureAiProvider(s, AiProviderType.GROK)))),
                settings(EndpointSpec.of("GET /ai/settings/branches/{branchId}", BRANCH,
                        (f, s, p) -> get("/ai/settings/branches/{id}", f.branch(s)))),
                settings(EndpointSpec.of("PUT /ai/settings/branches/{branchId}", BRANCH,
                        (f, s, p) -> withJson(put("/ai/settings/branches/{id}", f.branch(s)), json("notifyThresholdPercent", 80)))),
                settings(EndpointSpec.of("GET /ai/settings/branches/{branchId}/usage", BRANCH,
                        (f, s, p) -> get("/ai/settings/branches/{id}/usage", f.branch(s)))),
                settings(EndpointSpec.of("GET /ai/settings/usage-report", COMPANY,
                        (f, s, p) -> get("/ai/settings/usage-report").param("companyId", str(f.company(s))).param("from", "2026-01-01").param("to", "2026-01-31"))),
                settings(EndpointSpec.of("GET /ai/settings/usage-report/rates", COMPANY,
                        (f, s, p) -> get("/ai/settings/usage-report/rates").param("companyId", str(f.company(s))))),
                // D9: platform-owned rates; every tenant persona is refused.
                EndpointSpec.of("PUT /ai/settings/usage-report/rates", PLATFORM, (f, s, p) -> withJson(put("/ai/settings/usage-report/rates"),
                        json("companyId", f.company(s), "provider", "OPENAI", "model", "gpt-it", "inputRate", 1, "outputRate", 2))));
    }

    private static EndpointSpec assistant(String key, EndpointSpec.Request request) { return EndpointSpec.of(key, BRANCH, request); }

    private static EndpointSpec settings(EndpointSpec spec) { return spec.onlyFor(MANAGERS).deniedFor(AGENTS); }

    /** A conversation on {@code s}'s branch with one inbound message; the branch has a selected AI provider. */
    private static Long chat(TenantFixture f, Slot s) {
        f.ensureAiProvider(s);
        Long conversation = f.freshConversationFor(s, null, null);
        f.freshInboundMessage(conversation, "TEXT", "How much is it?");
        return conversation;
    }

    private static Long message(TenantFixture f, Slot s, String type) {
        f.ensureAiProvider(s);
        Long conversation = f.freshConversationFor(s, null, null);
        Long message = f.freshInboundMessage(conversation, type, "AUDIO".equals(type) ? null : "hola");
        if ("AUDIO".equals(type)) f.setMedia(message, "it-media-" + message, "audio/ogg");
        return message;
    }

    /** A lead on {@code s} with a linked conversation that has a message. */
    private static Long scoredLead(TenantFixture f, Slot s) {
        f.ensureAiProvider(s);
        Long lead = f.freshLeadAssignedTo(s, null);
        Long conversation = f.freshConversationFor(s, null, lead);
        f.freshInboundMessage(conversation, "TEXT", "Can you send a quote?");
        return lead;
    }

    /** Default AI provider and media answers for the allowed rows (the @MockBeans are reset after every row). */
    static void stub(AiMeteredCalls ai, WhatsAppGraphApiClient graph) {
        when(ai.chat(anyString(), any(), any(), any())).thenReturn(AiChatResponse.builder()
                .content("{\"score\":50,\"strengthLabel\":\"MEDIUM\",\"urgencyLabel\":\"LOW\",\"rationale\":\"IT\"}")
                .promptTokens(10).completionTokens(5).build());
        when(ai.transcribe(any(), any(), any(), any(), any())).thenReturn("IT transcript");
        when(graph.downloadMedia(any(), anyString(), any())).thenReturn(new byte[]{1, 2, 3});
    }

    private AiRows() { }
}
