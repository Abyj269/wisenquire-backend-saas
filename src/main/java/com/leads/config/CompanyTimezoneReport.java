package com.leads.config;

import com.leads.service.CompanyTimezones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Module 03 P1 (D15): at start-up, report (read-only) every company whose timezone is not one of
 * the 7 year-1 zones, so they can be fixed before tenant.timezone_allowlist is switched on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyTimezoneReport implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, organization_id, code, timezone FROM companies");
            long outside = rows.stream().filter(r -> !CompanyTimezones.allowed((String) r.get("timezone"))).peek(r ->
                    log.warn("D15: company {} (id {}, org {}) uses timezone '{}', which is not one of the 7 supported zones",
                            r.get("code"), r.get("id"), r.get("organization_id"), r.get("timezone"))).count();
            if (outside == 0) log.info("D15: every company uses one of the 7 supported timezones");
        } catch (RuntimeException e) {
            log.warn("D15 timezone report skipped: {}", e.getMessage());
        }
    }
}
