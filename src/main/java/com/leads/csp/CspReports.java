package com.leads.csp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * Module 02 P2 (Step 6.3): stores CSP violation reports aggregated in {@code csp_reports}. Accepts both browser
 * formats: {@code application/csp-report} ({"csp-report": {...}}) and the Reporting API
 * {@code application/reports+json} ([{"type": "csp-violation", "body": {...}}]). Keeps origins and paths only;
 * numeric path segments become {id} and long opaque ones {token}, so one page is one row and no token is stored.
 */
@Component
@RequiredArgsConstructor
public class CspReports {

    static final int MAX_REPORTS_PER_BODY = 20;

    private final JdbcTemplate jdbc;

    /** Records every violation in the parsed body; returns how many were recorded. Unknown shapes are ignored. */
    public int record(JsonNode body) {
        int n = 0;
        if (body == null) return 0;
        if (body.isArray()) {
            for (JsonNode r : body) {
                if (n >= MAX_REPORTS_PER_BODY) break;
                if (!"csp-violation".equals(r.path("type").asText())) continue;
                JsonNode b = r.path("body");
                n += store(text(b, "effectiveDirective"), text(b, "blockedURL"), text(b, "documentURL"), text(b, "disposition"));
            }
        } else if (body.has("csp-report")) {
            JsonNode b = body.path("csp-report");
            String directive = text(b, "effective-directive");
            if (directive.isEmpty()) directive = text(b, "violated-directive").split(" ")[0];
            n += store(directive, text(b, "blocked-uri"), text(b, "document-uri"), text(b, "disposition"));
        }
        return n;
    }

    private int store(String directive, String blocked, String document, String disposition) {
        String d = directive.toLowerCase(Locale.ROOT);
        if (!d.matches("[a-z-]{1,64}")) return 0;
        String disp = "enforce".equalsIgnoreCase(disposition) ? "enforce" : "report";
        jdbc.update("INSERT INTO csp_reports (directive, blocked, document_path, disposition, hits, first_seen, last_seen) "
                        + "VALUES (?, ?, ?, ?, 1, NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE hits = hits + 1, last_seen = NOW(6)",
                d, blockedSource(blocked), documentPath(document), disp);
        return 1;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).asText("").trim();
    }

    /** A URL becomes its origin; a keyword (inline, eval, data, blob, self) stays as is. */
    static String blockedSource(String blocked) {
        if (blocked.isEmpty()) return "(none)";
        if (!blocked.contains("://")) {
            String keyword = blocked.split("[:/?#]")[0].toLowerCase(Locale.ROOT);
            return cap(keyword.isEmpty() ? "(none)" : keyword, 200);
        }
        try {
            URI u = URI.create(blocked);
            if (u.getScheme() == null || u.getHost() == null) return "(invalid)";
            return cap(u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
                    + (u.getPort() > 0 ? ":" + u.getPort() : ""), 200);
        } catch (IllegalArgumentException e) {
            return "(invalid)";
        }
    }

    /** The page path only, with numeric and long opaque segments replaced, so ids and tokens are never stored. */
    static String documentPath(String document) {
        String path;
        try {
            path = document.isEmpty() ? "/" : URI.create(document).getPath();
        } catch (IllegalArgumentException e) {
            return "(invalid)";
        }
        if (path == null || path.isEmpty()) path = "/";
        StringBuilder out = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            out.append('/').append(seg.matches("\\d+") ? "{id}" : seg.length() > 24 ? "{token}" : seg);
        }
        return cap(out.length() == 0 ? "/" : out.toString(), 200);
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
