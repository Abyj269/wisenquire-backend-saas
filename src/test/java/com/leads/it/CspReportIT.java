package com.leads.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 6.3 (module 02 P2 frontend, CSP report-only): browsers' violation reports are collected without a login,
 * aggregated, and keep only origins and paths (no query strings, ids or tokens). The endpoint always answers 204.
 */
class CspReportIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private int post(String contentType, String body, String ip) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/public/csp-report")
                .contentType(contentType).content(body).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
    }

    private Map<String, Object> row(String blocked) {
        return jdbc.queryForMap("SELECT directive, blocked, document_path, disposition, hits FROM csp_reports WHERE blocked = ?", blocked);
    }

    @Test
    void bothReportFormatsAreAggregatedWithoutQueryStringsOrIds() throws Exception {
        String host = "https://cdn-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        String legacy = "{\"csp-report\":{\"document-uri\":\"https://app.example.test/leads/123?token=secret#x\","
                + "\"violated-directive\":\"script-src-elem 'self'\",\"blocked-uri\":\"" + host + "/lib.js?k=secret\",\"disposition\":\"report\"}}";
        assertEquals(204, post("application/csp-report", legacy, "10.9.0.1"));
        assertEquals(204, post("application/csp-report", legacy, "10.9.0.1"));
        Map<String, Object> r = row(host);
        assertEquals("script-src-elem", r.get("directive"));
        assertEquals("/leads/{id}", r.get("document_path"), "no query string, no id");
        assertEquals("report", r.get("disposition"));
        assertEquals(2L, ((Number) r.get("hits")).longValue(), "repeats are counted, not stored twice");

        String host2 = "https://maps-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        String reporting = "[{\"type\":\"csp-violation\",\"url\":\"https://app.example.test/reset-password/AbCdEfGhIjKlMnOpQrStUvWxYz012345\","
                + "\"body\":{\"documentURL\":\"https://app.example.test/reset-password/AbCdEfGhIjKlMnOpQrStUvWxYz012345\","
                + "\"effectiveDirective\":\"connect-src\",\"blockedURL\":\"" + host2 + ":8443/x\",\"disposition\":\"enforce\"}},"
                + "{\"type\":\"deprecation\",\"body\":{}}]";
        assertEquals(204, post("application/reports+json", reporting, "10.9.0.1"));
        Map<String, Object> r2 = row(host2 + ":8443");
        assertEquals("connect-src", r2.get("directive"));
        assertEquals("/reset-password/{token}", r2.get("document_path"), "a reset token in the path is never stored");
        assertEquals("enforce", r2.get("disposition"));
    }

    @Test
    void inlineViolationsKeepTheKeyword() throws Exception {
        String page = "/p" + UUID.randomUUID().toString().substring(0, 6);
        String body = "{\"csp-report\":{\"document-uri\":\"https://app.example.test" + page + "\",\"effective-directive\":\"script-src-elem\",\"blocked-uri\":\"inline\"}}";
        assertEquals(204, post("application/csp-report", body, "10.9.0.2"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM csp_reports WHERE blocked = 'inline' AND document_path = ?", Integer.class, page));
    }

    @Test
    void badOversizeAndFloodingReportsAreDroppedWith204() throws Exception {
        assertEquals(204, post("application/csp-report", "not json", "10.9.0.3"));
        assertEquals(204, post("application/csp-report", "{\"csp-report\":{\"effective-directive\":\"<script>\",\"blocked-uri\":\"https://evil.example.test\"}}", "10.9.0.3"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM csp_reports WHERE blocked = 'https://evil.example.test'", Integer.class), "invalid directive ignored");

        String big = "{\"csp-report\":{\"effective-directive\":\"img-src\",\"blocked-uri\":\"https://big.example.test\",\"x\":\"" + "a".repeat(17000) + "\"}}";
        assertEquals(204, post("application/csp-report", big, "10.9.0.3"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM csp_reports WHERE blocked = 'https://big.example.test'", Integer.class), "over 16 KB is dropped");

        String host = "https://flood-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        String body = "{\"csp-report\":{\"document-uri\":\"https://app.example.test/\",\"effective-directive\":\"img-src\",\"blocked-uri\":\"" + host + "\"}}";
        String ip = "10.9." + (1 + (int) (Math.random() * 200)) + "." + (1 + (int) (Math.random() * 200));
        for (int i = 0; i < 70; i++) assertEquals(204, post("application/csp-report", body, ip));
        assertEquals(60L, ((Number) row(host).get("hits")).longValue(), "60 per minute per IP, the rest dropped");
    }
}
