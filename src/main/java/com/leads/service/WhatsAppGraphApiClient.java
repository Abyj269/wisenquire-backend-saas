package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.WhatsAppConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Meta's WhatsApp Business Cloud (Graph) API. Every
 * branch has its own {@link WhatsAppConfiguration} (phone number id, WABA
 * id, access token), so every call here takes that config explicitly rather
 * than reading a single global credential — that's what makes messaging and
 * template management branch-wise instead of one fixed number for the whole
 * deployment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppGraphApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${whatsapp.cloud-api.base-url:https://graph.facebook.com/v20.0}")
    private String defaultBaseUrl;

    public String sendTextMessage(WhatsAppConfiguration config, String toWaId, String body) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toWaId,
                "type", "text",
                "text", Map.of("body", body)
        );
        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    public String sendTemplateMessage(WhatsAppConfiguration config, String toWaId, String templateName,
                                       String languageCode, List<Map<String, Object>> components) {
        Map<String, Object> template = components == null || components.isEmpty()
                ? Map.of("name", templateName, "language", Map.of("code", languageCode))
                : Map.of("name", templateName, "language", Map.of("code", languageCode), "components", components);
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toWaId,
                "type", "template",
                "template", template
        );
        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    public String sendInteractiveButtons(WhatsAppConfiguration config, String toWaId, String bodyText,
                                         List<Map<String, String>> buttons) {
        List<Map<String, Object>> buttonList = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            Map<String, String> btn = buttons.get(i);
            String id = btn.get("id");
            if (id == null || id.isBlank()) id = "btn_" + (i + 1);
            String title = btn.get("title");
            buttonList.add(Map.of(
                    "type", "reply",
                    "reply", Map.of("id", id, "title", title)
            ));
        }

        Map<String, Object> interactive = Map.of(
                "type", "button",
                "body", Map.of("text", bodyText),
                "action", Map.of("buttons", buttonList)
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", toWaId,
                "type", "interactive",
                "interactive", interactive
        );

        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    public String sendInteractiveList(WhatsAppConfiguration config, String toWaId, String bodyText,
                                      String buttonTitle, String sectionTitle, List<Map<String, String>> rows) {
        List<Map<String, Object>> rowList = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String id = row.get("id");
            if (id == null || id.isBlank()) id = "row_" + (i + 1);
            String title = row.get("title");
            String desc = row.get("description");
            Map<String, Object> rowMap = new java.util.HashMap<>();
            rowMap.put("id", id);
            rowMap.put("title", title);
            if (desc != null && !desc.isBlank()) {
                rowMap.put("description", desc);
            }
            rowList.add(rowMap);
        }

        Map<String, Object> section = new java.util.HashMap<>();
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            section.put("title", sectionTitle);
        }
        section.put("rows", rowList);

        Map<String, Object> action = Map.of(
                "button", buttonTitle != null && !buttonTitle.isBlank() ? buttonTitle : "View Options",
                "sections", List.of(section)
        );

        Map<String, Object> interactive = Map.of(
                "type", "list",
                "body", Map.of("text", bodyText),
                "action", action
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", toWaId,
                "type", "interactive",
                "interactive", interactive
        );

        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    /**
     * Uploads raw bytes to Meta's Media endpoint and returns the resulting
     * media id. Sending media works by reference to an id Meta itself hosts
     * (or a public link) — never by attaching bytes directly to the send
     * call — so every outbound image/video/audio/document goes through this
     * first.
     */
    public String uploadMedia(WhatsAppConfiguration config, byte[] data, String mimeType, String filename) {
        String url = resolveBaseUrl() + "/" + config.getPhoneNumberId() + "/media";

        ByteArrayResource fileResource = new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("messaging_product", "whatsapp");
        form.add("type", mimeType);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mimeType));
        form.add("file", new HttpEntity<>(fileResource, fileHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(config.getAccessToken());
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(form, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode id = body.get("id");
            if (id == null) throw new WhatsAppApiException("Meta media upload did not return an id.");
            return id.asText();
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        } catch (WhatsAppApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WhatsAppApiException("Unexpected error uploading media to Meta's WhatsApp API: " + ex.getMessage(), ex);
        }
    }

    /** image, video, audio, document, sticker — all share the same {id, caption?} envelope shape. */
    public String sendMediaMessage(WhatsAppConfiguration config, String toWaId, String mediaType, String mediaId, String caption, String filename) {
        Map<String, Object> mediaObj = "document".equals(mediaType) && filename != null
                ? (caption != null && !caption.isBlank() ? Map.of("id", mediaId, "caption", caption, "filename", filename) : Map.of("id", mediaId, "filename", filename))
                : (caption != null && !caption.isBlank() ? Map.of("id", mediaId, "caption", caption) : Map.of("id", mediaId));
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toWaId,
                "type", mediaType,
                mediaType, mediaObj
        );
        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    public String sendLocationMessage(WhatsAppConfiguration config, String toWaId, double latitude, double longitude, String name, String address) {
        Map<String, Object> location = new java.util.HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        if (name != null) location.put("name", name);
        if (address != null) location.put("address", address);
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toWaId,
                "type", "location",
                "location", location
        );
        JsonNode response = post(config, config.getPhoneNumberId() + "/messages", payload);
        return extractWamid(response);
    }

    /**
     * Resolves a media id to its bytes + mime type. Meta's media URLs are
     * short-lived signed links (~5 min), so this always does a fresh
     * resolve-then-fetch round trip — callers (WhatsAppMediaService) are
     * responsible for caching the result, not this client.
     */
    public byte[] downloadMedia(WhatsAppConfiguration config, String mediaId, String[] outMimeType) {
        JsonNode meta = get(config, mediaId);
        JsonNode urlNode = meta.get("url");
        if (urlNode == null) throw new WhatsAppApiException("Meta did not return a download URL for media " + mediaId);
        if (outMimeType != null && outMimeType.length > 0) {
            JsonNode mime = meta.get("mime_type");
            outMimeType[0] = mime != null ? mime.asText() : "application/octet-stream";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(config.getAccessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(urlNode.asText(), HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        }
    }

    /** Used by the "Test Connection" action on the branch config screen. */
    public JsonNode getPhoneNumberInfo(WhatsAppConfiguration config) {
        return get(config, config.getPhoneNumberId() + "?fields=verified_name,display_phone_number,quality_rating,code_verification_status");
    }

    /**
     * Step 1 of Meta's Resumable Upload API: opens an upload session for a
     * file of known length/type against the Meta App the access token
     * belongs to. Used only to get a header_handle for template Image/Video/
     * Document headers and Carousel card media — never for regular chat
     * media, which goes through the simpler {@link #uploadMedia} instead.
     */
    public String createUploadSession(WhatsAppConfiguration config, long fileLength, String mimeType, String fileName) {
        if (config.getAppId() == null || config.getAppId().isBlank()) {
            throw new WhatsAppApiException("This number has no Meta App ID configured — add it under WhatsApp Setup before creating templates with media headers.");
        }
        String url = resolveBaseUrl() + "/" + config.getAppId() + "/uploads"
                + "?file_length=" + fileLength
                + "&file_type=" + java.net.URLEncoder.encode(mimeType, java.nio.charset.StandardCharsets.UTF_8)
                + "&file_name=" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8);
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(config.getAccessToken()));
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode id = body.get("id");
            if (id == null) throw new WhatsAppApiException("Meta did not return an upload session id.");
            return id.asText();
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        } catch (WhatsAppApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WhatsAppApiException("Unexpected error opening a Meta upload session: " + ex.getMessage(), ex);
        }
    }

    /** Step 2 of the Resumable Upload API: sends the file bytes to the session opened by {@link #createUploadSession} and returns the resulting header_handle. */
    public String uploadSessionFile(WhatsAppConfiguration config, String uploadSessionId, byte[] data) {
        String url = resolveBaseUrl() + "/" + uploadSessionId;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "OAuth " + config.getAccessToken());
        headers.set("file_offset", "0");
        HttpEntity<byte[]> entity = new HttpEntity<>(data, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode handle = body.get("h");
            if (handle == null) throw new WhatsAppApiException("Meta did not return a header handle for the uploaded media.");
            return handle.asText();
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        } catch (WhatsAppApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WhatsAppApiException("Unexpected error uploading media to Meta's upload session: " + ex.getMessage(), ex);
        }
    }

    public List<JsonNode> listTemplates(WhatsAppConfiguration config) {
        List<JsonNode> templates = new ArrayList<>();
        String path = config.getWabaId() + "/message_templates?fields=id,name,status,category,correct_category,language,components,quality_score,rejected_reason,parameter_format&limit=100";
        java.util.Set<String> cursors = new java.util.HashSet<>();
        String after = null;
        do {
            JsonNode response = get(config, path + (after == null ? "" : "&after=" + java.net.URLEncoder.encode(after, java.nio.charset.StandardCharsets.UTF_8)));
            JsonNode data = response.get("data");
            if (data == null || !data.isArray()) throw new WhatsAppApiException("Meta returned an invalid template list.");
            data.forEach(templates::add);
            JsonNode next = response.path("paging").path("next");
            if (next.isMissingNode() || next.isNull() || next.asText().isBlank()) break;
            after = response.path("paging").path("cursors").path("after").asText();
            if (after.isBlank() || !cursors.add(after) || cursors.size() > 1000)
                throw new WhatsAppApiException("Meta template pagination did not advance. Please refresh.");
            // Reconstruct on the configured Graph origin; never forward credentials to a paging URL.
        } while (true);
        return templates;
    }

    public JsonNode requireTemplate(WhatsAppConfiguration config, String id) {
        return listTemplates(config).stream().filter(t -> id.equals(t.path("id").asText())).findFirst()
            .orElseThrow(() -> new WhatsAppApiException("Template does not belong to this WhatsApp business account."));
    }

    public JsonNode createTemplate(WhatsAppConfiguration config, Map<String, Object> payload) {
        return post(config, config.getWabaId() + "/message_templates", payload);
    }

    /** Edits an existing template's content in place (POST /{template-id}) — the name and language can't be changed this way, only category/components. */
    public JsonNode updateTemplate(WhatsAppConfiguration config, String templateId, Map<String, Object> payload) {
        return post(config, templateId, payload);
    }

    public void deleteTemplate(WhatsAppConfiguration config, String templateName) {
        String url = resolveBaseUrl() + "/" + config.getWabaId() + "/message_templates?name=" + templateName;
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(config.getAccessToken()));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        }
    }

    private JsonNode post(WhatsAppConfiguration config, String path, Map<String, Object> payload) {
        String url = resolveBaseUrl() + "/" + path;
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, authHeaders(config.getAccessToken()));
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        } catch (Exception ex) {
            throw new WhatsAppApiException("Unexpected error calling Meta's WhatsApp API: " + ex.getMessage(), ex);
        }
    }

    private JsonNode get(WhatsAppConfiguration config, String pathWithQuery) {
        String url = resolveBaseUrl() + "/" + pathWithQuery;
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(config.getAccessToken()));
        try {
            ResponseEntity<String> response = restTemplate.exchange(java.net.URI.create(url), HttpMethod.GET, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException ex) {
            throw toApiException(ex);
        } catch (ResourceAccessException ex) {
            throw new WhatsAppApiException("Could not reach Meta's WhatsApp API — check network access and the base URL.", ex);
        } catch (Exception ex) {
            throw new WhatsAppApiException("Unexpected error calling Meta's WhatsApp API: " + ex.getMessage(), ex);
        }
    }

    private String resolveBaseUrl() {
        return defaultBaseUrl;
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private String extractWamid(JsonNode response) {
        JsonNode messages = response.get("messages");
        if (messages != null && messages.isArray() && messages.size() > 0) {
            JsonNode id = messages.get(0).get("id");
            if (id != null) return id.asText();
        }
        return "wamid." + System.currentTimeMillis();
    }

    private WhatsAppApiException toApiException(HttpStatusCodeException ex) {
        String reason = ex.getStatusCode().toString();
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            JsonNode error = body.get("error");
            if (error != null && error.get("message") != null) {
                reason = error.get("message").asText();
            }
        } catch (Exception ignored) {
            // Meta didn't return the expected error shape — fall back to the raw status.
        }
        log.warn("WhatsApp Graph API call failed: {}", reason);
        return new WhatsAppApiException(reason);
    }
}
