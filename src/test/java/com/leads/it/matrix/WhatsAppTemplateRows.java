package com.leads.it.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leads.service.WhatsAppGraphApiClient;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 13 P1: the 6 WhatsAppTemplateController endpoints, scoped through the connection
 * ({@code configId}, BRANCH). Templates live in Meta, so the allowed rows run against the mocked
 * Graph client ({@link #stubGraph}); the denied rows also assert Meta was never called.
 */
final class WhatsAppTemplateRows {

    static final String NAME = "it_template";
    static final String TEMPLATE_ID = "it-tpl-1";

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /whatsapp/templates", BRANCH, (f, s, p) -> get("/whatsapp/templates").param("configId", str(f.config(s)))),
                EndpointSpec.of("POST /whatsapp/templates", BRANCH, (f, s, p) -> withJson(post("/whatsapp/templates"), body(f.config(s)))),
                EndpointSpec.of("PUT /whatsapp/templates/{templateId}", BRANCH, (f, s, p) -> withJson(put("/whatsapp/templates/{templateId}", TEMPLATE_ID), body(f.config(s)))),
                EndpointSpec.of("DELETE /whatsapp/templates/{name}", BRANCH, (f, s, p) -> delete("/whatsapp/templates/{name}", NAME).param("configId", str(f.config(s)))),
                EndpointSpec.of("POST /whatsapp/templates/media", BRANCH, (f, s, p) -> multipart("/whatsapp/templates/media")
                        .file(new MockMultipartFile("file", "sample.png", "image/png", new byte[]{1, 2, 3}))
                        .param("configId", str(f.config(s)))),
                EndpointSpec.of("GET /whatsapp/templates/usage", BRANCH, (f, s, p) -> get("/whatsapp/templates/usage")
                        .param("configId", str(f.config(s))).param("from", "2026-01-01").param("to", "2026-12-31")));
    }

    private static String body(Long configId) {
        return json("configId", configId, "name", NAME, "category", "UTILITY", "language", "en_US", "bodyText", "Your order is ready.");
    }

    /** Default Meta answers for the allowed rows (the @MockBean is reset after every row). */
    static void stubGraph(WhatsAppGraphApiClient graph) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode().put("id", TEMPLATE_ID).put("name", NAME).put("language", "en_US")
                .put("status", "APPROVED").put("category", "UTILITY");
        template.putArray("components");
        when(graph.requireTemplate(any(), anyString())).thenReturn(template);
        when(graph.createTemplate(any(), anyMap())).thenReturn(mapper.createObjectNode().put("id", TEMPLATE_ID).put("status", "PENDING"));
        when(graph.createUploadSession(any(), anyLong(), anyString(), anyString())).thenReturn("it-upload-session");
        when(graph.uploadSessionFile(any(), anyString(), any())).thenReturn("it-header-handle");
    }

    private WhatsAppTemplateRows() { }
}
