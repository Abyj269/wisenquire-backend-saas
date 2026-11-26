package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.config.StompSecurityInterceptor;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.WhatsAppConversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 12 P1 (D14) with own_scope.enforced ON for org A: OWN-LIST (and counts), OWN-RECORD on the
 * conversation endpoints, find-by-phone and start-conversation, ASSIGNED_TO_ME = the OWN rule,
 * OWN-PUSH (no company/branch topics for OWN users, their own queue instead), and webhook batch
 * resilience (one unknown number no longer drops the batch).
 */
class OwnScopeConversationsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    @Autowired StompSecurityInterceptor stomp;
    private final ObjectMapper json = new ObjectMapper();
    private Long a1, a2;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
    }

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        flags.clearOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'));
    }

    private void enforce() { flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), true, "it", "OwnScopeConversationsIT"); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private JsonNode list(Persona p, String filter) throws Exception {
        MockHttpServletResponse r = call(p, get("/whatsapp/conversations").param("companyId", String.valueOf(fixture.company(Slot.A1a)))
                .param("filter", filter).param("size", "100"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString());
    }

    private Set<Long> ids(JsonNode page) {
        Set<Long> out = new HashSet<>();
        for (JsonNode c : page.path("content")) out.add(c.path("id").asLong());
        return out;
    }

    // ---- OWN-LIST / ASSIGNED_TO_ME ------------------------------------------------------------------

    @Test
    void anOwnAgentListsOnlyTheirConversationsAndTheCountsMatch() throws Exception {
        enforce();
        Long asAgent = fixture.freshConversationFor(Slot.A1a, a1, null);
        Long viaMyLead = fixture.freshConversationFor(Slot.A1a, a2, fixture.freshLeadAssignedTo(Slot.A1a, a1));
        Long notMine = fixture.freshConversationFor(Slot.A1a, a2, fixture.freshLeadAssignedTo(Slot.A1a, a2));
        Long unassigned = fixture.freshConversationFor(Slot.A1a, null, null);

        JsonNode all = list(Persona.AGENT_OWN_1, "ALL");
        assertTrue(ids(all).containsAll(Set.of(asAgent, viaMyLead)));
        assertFalse(ids(all).contains(notMine), "OWN-LIST");
        assertFalse(ids(all).contains(unassigned), "unassigned hidden unless include_unassigned");
        assertEquals(all.path("totalElements").asLong(), all.path("counts").path("all").asLong());
        assertEquals(all.path("counts").path("all").asLong(), all.path("counts").path("assignedToMe").asLong(),
                "ASSIGNED_TO_ME = everything an OWN agent can see (without include_unassigned)");

        flags.setOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'), true, "it", "test");
        assertTrue(ids(list(Persona.AGENT_OWN_1, "ALL")).contains(unassigned));

        assertTrue(ids(list(Persona.BRANCH_MGR, "ALL")).containsAll(Set.of(asAgent, viaMyLead, notMine, unassigned)), "LEVEL unchanged");
    }

    @Test
    void assignedToMeIncludesChatsWhereIAmTheAgentForEveryone() throws Exception {
        Long mgr = fixture.user('A', Persona.BRANCH_MGR);
        Long agentChat = fixture.freshConversationFor(Slot.A1a, mgr, null);
        assertTrue(ids(list(Persona.BRANCH_MGR, "ASSIGNED_TO_ME")).contains(agentChat), "was only the lead's assignee before");
    }

    // ---- OWN-RECORD ---------------------------------------------------------------------------------

    @Test
    void anotherAgentsConversationIsRefusedOnItsEndpoints() throws Exception {
        enforce();
        Long theirs = fixture.freshConversationFor(Slot.A1a, a2, null);
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/whatsapp/conversations/{id}", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, get("/whatsapp/conversations/{id}/messages", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/whatsapp/conversations/{id}/mark-read", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/whatsapp/conversations/{id}/block", theirs)).getStatus());
        assertEquals(403, call(Persona.AGENT_OWN_1, post("/whatsapp/messages/send").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", theirs, "message", "hi")))).getStatus());
    }

    @Test
    void findByPhoneAndStartDoNotLeakAnotherAgentsChat() throws Exception {
        enforce();
        Long theirs = fixture.freshConversationFor(Slot.A1a, a2, null);
        String waId = fixture.waIdOf(theirs);
        MockHttpServletResponse found = call(Persona.AGENT_OWN_1, get("/whatsapp/conversations/find")
                .param("companyId", String.valueOf(fixture.company(Slot.A1a))).param("phone", waId));
        assertEquals(200, found.getStatus());
        assertTrue(found.getContentAsString().isBlank() || json.readTree(found.getContentAsString()).isNull(), "not found for agent 1: " + found.getContentAsString());

        MockHttpServletResponse start = call(Persona.AGENT_OWN_1, post("/whatsapp/conversations/start").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("configId", fixture.config(Slot.A1a), "phone", waId, "customerName", "X"))));
        assertEquals(403, start.getStatus(), "was returned to anyone who knew the number");

        String newPhone = "9715" + fixture.phone().substring(5);
        MockHttpServletResponse fresh = call(Persona.AGENT_OWN_1, post("/whatsapp/conversations/start").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("configId", fixture.config(Slot.A1a), "phone", newPhone, "customerName", "IT New"))));
        assertEquals(200, fresh.getStatus(), fresh.getContentAsString());
        Long id = json.readTree(fresh.getContentAsString()).path("id").asLong();
        assertEquals(String.valueOf(a1), fixture.state(WhatsAppConversation.class, id, c -> c.getAssignedAgent() == null ? null : c.getAssignedAgent().getId()),
                "a new chat started by an OWN agent is theirs");
    }

    // ---- OWN-PUSH ---------------------------------------------------------------------------------------

    private Message<byte[]> frame(StompCommand command, String session, String destination, String token) {
        StompHeaderAccessor h = StompHeaderAccessor.create(command);
        h.setSessionId(session);
        if (destination != null) h.setDestination(destination);
        if (token != null) h.setNativeHeader("Authorization", "Bearer " + token);
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    @Test
    void anOwnAgentCannotSubscribeToTheBranchTopicButGetsTheirQueue() throws Exception {
        enforce();
        String session = "it-own-push-" + fixture.phone();
        stomp.preSend(frame(StompCommand.CONNECT, session, null, fixture.token('A', Persona.AGENT_OWN_1)), null);
        stomp.afterSendCompletion(null, null, true, null);
        assertThrows(RuntimeException.class, () -> stomp.preSend(frame(StompCommand.SUBSCRIBE, session, "/topic/whatsapp/branch/" + fixture.branch(Slot.A1a), null), null),
                "the branch topic carries every chat of the branch");
        assertDoesNotThrow(() -> stomp.preSend(frame(StompCommand.SUBSCRIBE, session, "/user/queue/whatsapp", null), null));
        Long mine = fixture.freshConversationFor(Slot.A1a, a1, null);
        assertDoesNotThrow(() -> stomp.preSend(frame(StompCommand.SUBSCRIBE, session, "/topic/whatsapp/conversation/" + mine, null), null));
        Long theirs = fixture.freshConversationFor(Slot.A1a, a2, null);
        assertThrows(RuntimeException.class, () -> stomp.preSend(frame(StompCommand.SUBSCRIBE, session, "/topic/whatsapp/conversation/" + theirs, null), null));
        stomp.preSend(frame(StompCommand.DISCONNECT, session, null, null), null);

        String mgrSession = "it-mgr-push-" + fixture.phone();
        stomp.preSend(frame(StompCommand.CONNECT, mgrSession, null, fixture.token('A', Persona.BRANCH_MGR)), null);
        assertDoesNotThrow(() -> stomp.preSend(frame(StompCommand.SUBSCRIBE, mgrSession, "/topic/whatsapp/branch/" + fixture.branch(Slot.A1a), null), null), "LEVEL unchanged");
        stomp.preSend(frame(StompCommand.DISCONNECT, mgrSession, null, null), null);
    }

    // ---- webhook batch resilience ---------------------------------------------------------------------

    @Test
    void oneUnknownNumberNoLongerDropsTheRestOfTheBatch() throws Exception {
        String secret = "it-webhook-secret";
        fixture.setAppSecret(Slot.A1a, secret);
        String wamid = "wamid.IT" + fixture.phone().substring(4);
        String body = """
            {"object":"whatsapp_business_account","entry":[{"id":"it-waba","changes":[
              {"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"0","phone_number_id":"it-unknown-number"},
                "messages":[{"from":"971500000001","id":"wamid.unknown","timestamp":"1700000000","type":"text","text":{"body":"lost"}}]}},
              {"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"0","phone_number_id":"it-pnid-A1a"},
                "contacts":[{"profile":{"name":"IT Batch"},"wa_id":"971509998877"}],
                "messages":[{"from":"971509998877","id":"%s","timestamp":"1700000000","type":"text","text":{"body":"kept"}}]}}]}]}
            """.formatted(wamid);
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(raw));

        int status = mvc.perform(MockMvcRequestBuilders.post("/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(raw).header("X-Hub-Signature-256", signature)).andReturn().getResponse().getStatus();
        assertEquals(200, status);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages WHERE wamid = ?", Integer.class, wamid), "the known number's message is stored");

        int bad = mvc.perform(MockMvcRequestBuilders.post("/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(raw).header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))).andReturn().getResponse().getStatus();
        assertTrue(bad == 401 || bad == 403, "a bad signature is still refused: " + bad);
    }
}
