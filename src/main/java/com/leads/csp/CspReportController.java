package com.leads.csp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.config.ClientIpResolver;
import com.leads.service.RequestRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Module 02 P2 (Step 6.3): collects Content-Security-Policy violation reports sent by browsers (the frontend's
 * report-only policy points here). Public, no tenant data. Always answers 204, so a browser never retries and a
 * caller learns nothing; over-size bodies, bad JSON and callers over the rate limit are silently dropped.
 */
@Slf4j
@RestController
@RequestMapping("/public/csp-report")
@RequiredArgsConstructor
@Tag(name = "CSP reports", description = "Content-Security-Policy violation reports from browsers")
public class CspReportController {

    static final int MAX_BODY_BYTES = 16 * 1024;
    static final int PER_IP_PER_MINUTE = 60;

    private final CspReports reports;
    private final ObjectMapper mapper;
    private final RequestRateLimiter limiter;
    private final ClientIpResolver clientIp;

    @PostMapping
    @Operation(summary = "Record CSP violation reports", description = "Accepts application/csp-report and application/reports+json. "
            + "Always 204. At most 16 KB per body and 60 bodies per minute per IP; anything else is dropped.")
    public ResponseEntity<Void> report(@RequestBody(required = false) byte[] body, HttpServletRequest http) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) return ResponseEntity.noContent().build();
        try {
            limiter.check("csp-report:" + clientIp.resolve(http), PER_IP_PER_MINUTE, 60_000);
            reports.record(mapper.readTree(body));
        } catch (ResponseStatusException tooMany) {
            // over the limit: dropped
        } catch (Exception e) {
            log.debug("CSP report dropped: {}", e.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }
}
