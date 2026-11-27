package com.leads.controller;

import com.leads.model.enums.AiProviderType;
import com.leads.service.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/ai/settings/usage-report") @RequiredArgsConstructor
public class AiUsageReportController {
    private final TenantAccess access;
    private final NamedParameterJdbcTemplate jdbc;
    private final com.leads.service.CompanyClock companyClock;
    /** D9: rates are platform-wide; {@code companyId} is accepted for compatibility and ignored. */
    public record Rate(Long companyId, @NotNull AiProviderType provider,
                       @NotBlank @Size(max=100) String model,
                       @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=8) BigDecimal inputRate,
                       @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=8) BigDecimal outputRate) {}

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    public Map<String,Object> report(@RequestParam Long companyId, @RequestParam(required=false) Long branchId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        access.scope(companyId, branchId);
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from,to) > 366) throw new IllegalArgumentException("Choose a date range of at most 367 days, with the end after the start.");
        Map<String,Object> params = new HashMap<>(); params.put("company", companyId);
        // T-2 (D15, module 18 part 2): ai_call_usage.started_at is UTC. The range is the company's local days, turned
        // into UTC bounds, and each row is bucketed into its company day (fixed-offset zones, D15 option A).
        com.leads.model.entity.Company company = access.company(companyId);
        java.time.ZoneId zone = companyClock.zone(company);
        var range = companyClock.range(company, from, to);
        params.put("from", Timestamp.valueOf(toUtc(range.start()))); params.put("to", Timestamp.valueOf(toUtc(range.endExclusive())));
        params.put("offset", zone.getRules().getOffset(java.time.Instant.now()).getTotalSeconds() / 60);
        String scope = " company_id=:company";
        if (branchId != null) { scope += " AND branch_id=:branch"; params.put("branch",branchId); }
        List<Long> branches = access.branchIds();
        if (branches != null) {
            scope += branches.isEmpty() ? " AND 1=0" : " AND branch_id IN (:branches)";
            if (!branches.isEmpty()) params.put("branches",branches);
        }
        var first = jdbc.queryForList("SELECT MIN(started_at) AS firstRecordedAt FROM ai_call_usage WHERE"+scope, params);
        String where = scope + " AND started_at>=:from AND started_at<:to";
        var totals = jdbc.queryForMap("SELECT COUNT(*) AS requests, COALESCE(SUM(status='SUCCEEDED'),0) AS succeeded, COALESCE(SUM(status='FAILED'),0) AS failed, COALESCE(SUM(status='PENDING'),0) AS pending, COALESCE(SUM(input_tokens),0) AS inputTokens, COALESCE(SUM(output_tokens),0) AS outputTokens, COALESCE(SUM(estimated_usd),0) AS estimatedUsd, COALESCE(SUM(estimated_usd IS NULL),0) AS unpriced FROM ai_call_usage WHERE"+where,params);
        var rows = jdbc.queryForList("SELECT DATE(DATE_ADD(started_at, INTERVAL :offset MINUTE)) AS day, branch_id AS branchId, provider, model_name AS model, feature, COUNT(*) AS requests, COALESCE(SUM(input_tokens),0) AS inputTokens, COALESCE(SUM(output_tokens),0) AS outputTokens, COALESCE(SUM(estimated_usd),0) AS estimatedUsd, SUM(estimated_usd IS NULL) AS unpriced FROM ai_call_usage WHERE"+where+" GROUP BY DATE(DATE_ADD(started_at, INTERVAL :offset MINUTE)),branch_id,provider,model_name,feature ORDER BY day DESC,branchId,provider,model,feature", params);
        Map<String,Object> result = new HashMap<>(); result.put("totals",totals); result.put("rows",rows);
        params.put("fromDay",from.toString()); params.put("toDay",to.toString());
        result.put("guardCounters", jdbc.queryForMap("SELECT COALESCE(SUM(request_count),0) AS requests, COALESCE(SUM(estimated_tokens),0) AS estimatedTokens FROM ai_usage_counters WHERE"+scope+" AND period_type='DAY' AND period_key>=:fromDay AND period_key<=:toDay",params));
        Object firstUtc = first.get(0).get("firstRecordedAt");
        // Returned as server wall-clock like every timestamp (the FE renders it in the company zone).
        result.put("firstRecordedAt", firstUtc instanceof Timestamp t ? fromUtc(t.toLocalDateTime()) : firstUtc);
        result.put("currency","USD"); result.put("timezone", zone.getId());
        return result;
    }

    /** D9 (module 18 part 2): the platform-owned rates (the same for every company). */
    @GetMapping("/rates")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    public List<Map<String,Object>> rates(@RequestParam Long companyId) {
        access.company(companyId);
        return jdbc.queryForList("SELECT provider,model_name AS model,input_per_million AS inputRate,output_per_million AS outputRate FROM platform_ai_model_rates ORDER BY provider,model_name", Map.of());
    }

    /** D9 (module 18 part 2): only the platform admin sets rates; tenant admins get 403. Module 21 moves this to /platform/ai-rates. */
    @PutMapping("/rates")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public void saveRate(@Valid @RequestBody Rate rate) {
        jdbc.update("INSERT INTO platform_ai_model_rates(provider,model_name,input_per_million,output_per_million) VALUES(:provider,:model,:input,:output) ON DUPLICATE KEY UPDATE input_per_million=:input,output_per_million=:output",
                Map.of("provider",rate.provider().name(),"model",rate.model().trim(),"input",rate.inputRate(),"output",rate.outputRate()));
    }

    private static java.time.LocalDateTime toUtc(java.time.LocalDateTime server) {
        return server.atZone(com.leads.service.CompanyClock.SERVER_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private static java.time.LocalDateTime fromUtc(java.time.LocalDateTime utc) {
        return utc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(com.leads.service.CompanyClock.SERVER_ZONE).toLocalDateTime();
    }
}
