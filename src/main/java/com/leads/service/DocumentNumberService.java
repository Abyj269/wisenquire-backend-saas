package com.leads.service;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Lead, quotation and delivery numbers: {@code <type>-<company>-<year>-<seq>}, one sequence per
 * company, type and year.
 * <ul>
 *   <li>Module 06 P1 (D15): the year is the company-local year, so a lead created at 00:10 IST on
 *       1 January is numbered in the new year (was the UTC year).</li>
 *   <li>Module 06 P1: with flag {@code documents.company_code_prefix} ON for the org, the company part
 *       is the company's random {@code document_code} (V70), not its internal id, so a number no longer
 *       tells other tenants how many companies exist. OFF: the id, as before. Existing numbers never change.</li>
 * </ul>
 * A number is only a label: every lookup by number still goes through TenantAccess.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private static final Set<String> TYPES = Set.of("LD", "QT", "DL");
    /** Upper-case letters and digits: same alphabet as the V70 backfill (base 36). */
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CompanyClock companyClock;
    private final FeatureFlags flags;

    @Transactional
    public String next(String type, Long companyId) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Invalid document type.");
        Map<String, Object> company = jdbc.queryForMap(
                "SELECT organization_id, timezone, document_code FROM companies WHERE id = ?", companyId);
        int year = ZonedDateTime.now(clock).withZoneSameInstant(companyClock.zone((String) company.get("timezone"))).getYear();
        jdbc.update("INSERT INTO document_sequences(company_id, document_type, document_year, next_value) VALUES (?, ?, ?, 1) ON DUPLICATE KEY UPDATE next_value=next_value+1", companyId, type, year);
        Long value = jdbc.queryForObject("SELECT next_value FROM document_sequences WHERE company_id=? AND document_type=? AND document_year=? FOR UPDATE", Long.class, companyId, type, year);
        String code = (String) company.get("document_code");
        long orgId = ((Number) company.get("organization_id")).longValue();
        if (code != null && !code.isBlank() && flags.isOn(FeatureFlag.DOCUMENTS_COMPANY_CODE_PREFIX, orgId)) {
            return String.format("%s-%s-%d-%06d", type, code, year, value);
        }
        return String.format("%s-%d-%d-%06d", type, companyId, year, value);
    }

    /** A random 6-character company code (36^6, about 2.2 billion values; the unique key catches the rare clash). */
    public static String randomCompanyCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
