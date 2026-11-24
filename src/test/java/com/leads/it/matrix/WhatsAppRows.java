package com.leads.it.matrix;

import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.WhatsAppConversation;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 12 WhatsApp inbox: the 13 authenticated endpoints of WhatsAppController. The two
 * webhook endpoints are public and signature-checked; they are in exempt-endpoints.txt.
 * The Meta Graph client is a mock (AbstractIntegrationTest).
 */
final class WhatsAppRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /whatsapp/conversations", BRANCH, (f, s, p) -> get("/whatsapp/conversations")
                        .param("companyId", str(f.company(s))).param("configId", str(f.config(s)))),

                EndpointSpec.of("GET /whatsapp/conversations/find", COMPANY, (f, s, p) -> get("/whatsapp/conversations/find")
                        .param("companyId", str(f.company(s))).param("phone", "971500000000")),

                EndpointSpec.of("GET /whatsapp/conversations/{id}", BRANCH,
                        (f, s, p) -> get("/whatsapp/conversations/{id}", f.conversation(s))),

                EndpointSpec.of("GET /whatsapp/conversations/{id}/messages", BRANCH,
                        (f, s, p) -> get("/whatsapp/conversations/{id}/messages", f.conversation(s))),

                EndpointSpec.of("POST /whatsapp/conversations/start", BRANCH, (f, s, p) -> withJson(
                        post("/whatsapp/conversations/start"),
                        json("configId", f.config(s), "customerName", "IT Start", "phone", f.phone()))),

                EndpointSpec.of("POST /whatsapp/messages/send", BRANCH, (f, s, p) -> withJson(post("/whatsapp/messages/send"),
                        json("conversationId", watched(f, p, f.freshConversation(s)), "message", "IT matrix", "messageType", "TEXT"))),

                EndpointSpec.of("POST /whatsapp/messages/send-media", BRANCH, (f, s, p) -> multipart("/whatsapp/messages/send-media")
                        .file(new MockMultipartFile("file", "it.txt", "text/plain", "IT matrix".getBytes()))
                        .param("conversationId", str(watched(f, p, f.freshConversation(s))))),

                EndpointSpec.of("GET /whatsapp/messages/{messageId}/media", BRANCH,
                        (f, s, p) -> get("/whatsapp/messages/{messageId}/media", f.message(s)))
                        .noAllowedRow("the fixture's messages are text, so there is no media to return"),

                EndpointSpec.of("POST /whatsapp/conversations/{id}/mark-read", BRANCH,
                        (f, s, p) -> post("/whatsapp/conversations/{id}/mark-read", watched(f, p, f.freshConversation(s)))),

                EndpointSpec.of("PUT /whatsapp/conversations/{id}/pin", BRANCH,
                        (f, s, p) -> put("/whatsapp/conversations/{id}/pin", watched(f, p, f.freshConversation(s))).param("pinned", "true")),

                EndpointSpec.of("POST /whatsapp/conversations/{id}/mark-unread", BRANCH,
                        (f, s, p) -> post("/whatsapp/conversations/{id}/mark-unread", watched(f, p, f.freshConversation(s)))),

                EndpointSpec.of("POST /whatsapp/conversations/{id}/block", BRANCH,
                        (f, s, p) -> post("/whatsapp/conversations/{id}/block", watched(f, p, f.freshConversation(s)))),

                EndpointSpec.of("POST /whatsapp/create-lead", BRANCH, (f, s, p) -> withJson(post("/whatsapp/create-lead"), json(
                        "conversationId", watched(f, p, f.freshConversation(s)), "branchId", f.branch(s),
                        "firstName", "IT Chat Lead", "phone", f.phone(), "sourceId", f.source(s)))));
    }

    private static Long watched(TenantFixture f, EndpointSpec.Probe p, Long id) {
        p.watch(() -> f.state(WhatsAppConversation.class, id, c -> c.isPinned() + "|" + c.isManuallyUnread() + "|"
                + c.getUnreadCount() + "|" + c.getChatStatus() + "|" + (c.getLead() == null ? null : c.getLead().getId())
                + "|" + c.getMessages().size()));
        return id;
    }

    private WhatsAppRows() { }
}
