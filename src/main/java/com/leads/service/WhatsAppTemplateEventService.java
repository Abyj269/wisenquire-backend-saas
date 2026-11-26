package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Signed WABA events are an audit trail; live Graph category remains authoritative. */
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateEventService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final com.leads.repository.WhatsAppConfigurationRepository configs;
    public static final Set<String> FIELDS = Set.of("template_category_update", "message_template_status_update", "message_template_quality_update");

    @Transactional
    public void recordVerified(JsonNode payload) {
        for (var entry : payload.path("entry")) for (var change : entry.path("changes")) {
            String field = change.path("field").asText();
            if (!FIELDS.contains(field)) continue;
            JsonNode value = change.path("value");
            String waba = entry.path("id").asText(), id = value.path("message_template_id").asText();
            long epoch = entry.path("time").asLong(0);
            // Status/quality event variants may omit entry.time. Keep zero for
            // deterministic deduplication; never use these events to override live Graph data.
            if (waba.isBlank() || id.isBlank() || waba.length() > 100 || id.length() > 100 || epoch < 0)
                throw new IllegalArgumentException("Template event requires WABA and template ID.");
            // Module 13 P1: the verifier skips a change for an account with no active number; do not store it either.
            if (configs.findByWabaIdAndIsActiveTrue(waba).isEmpty()) continue;
            String json = value.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > 60000) throw new IllegalArgumentException("Template event exceeds storage limit.");
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((waba + "|" + id + "|" + field + "|" + epoch + "|" + json).getBytes(StandardCharsets.UTF_8)));
                jdbc.update("INSERT INTO whatsapp_template_events (waba_id,template_id,event_field,event_epoch,event_hash,event_json) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_hash=VALUES(event_hash)",
                    waba, id, field, epoch, hash, json);
            } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        }
    }

    public Map<String, JsonNode> latestCategoryEvents(String waba) {
        Map<String, JsonNode> result = new HashMap<>();
        jdbc.query("SELECT e.template_id,e.event_json FROM whatsapp_template_events e WHERE e.waba_id=? AND e.event_field='template_category_update' AND NOT EXISTS (SELECT 1 FROM whatsapp_template_events n WHERE n.waba_id=e.waba_id AND n.template_id=e.template_id AND n.event_field=e.event_field AND (n.event_epoch>e.event_epoch OR (n.event_epoch=e.event_epoch AND n.id>e.id)))", rs -> {
            try { result.put(rs.getString(1), mapper.readTree(rs.getString(2))); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Invalid stored template event", ex); }
        }, waba);
        return result;
    }
}
