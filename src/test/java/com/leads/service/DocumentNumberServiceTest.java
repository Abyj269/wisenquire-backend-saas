package com.leads.service;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Module 06 P1: company-local year (D15) and the company-code format of document numbers. */
class DocumentNumberServiceTest {

    /** 2026-12-31 18:40 UTC = 2027-01-01 00:10 in India, still 2026-12-31 22:40 in Dubai. */
    private static final Clock NEW_YEAR_IN_INDIA = Clock.fixed(Instant.parse("2026-12-31T18:40:00Z"), ZoneOffset.UTC);

    private DocumentNumberService service(String timezone, boolean codeFlag) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(12L))).thenReturn(Map.of("organization_id", 3L, "timezone", timezone, "document_code", "K7Q2XM"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(45L);
        FeatureFlags flags = mock(FeatureFlags.class);
        when(flags.isOn(FeatureFlag.DOCUMENTS_COMPANY_CODE_PREFIX, 3L)).thenReturn(codeFlag);
        return new DocumentNumberService(jdbc, NEW_YEAR_IN_INDIA, new CompanyClock(NEW_YEAR_IN_INDIA), flags);
    }

    @Test
    void theYearIsTheCompanysLocalYear() {
        assertEquals("LD-12-2027-000045", service("Asia/Kolkata", false).next("LD", 12L), "00:10 IST on 1 January: the new year");
        assertEquals("LD-12-2026-000045", service("Asia/Dubai", false).next("LD", 12L), "22:40 in Dubai: still the old year");
    }

    @Test
    void withTheFlagOnTheCompanyCodeReplacesTheId() {
        assertEquals("QT-K7Q2XM-2027-000045", service("Asia/Kolkata", true).next("QT", 12L));
        assertEquals("DL-12-2027-000045", service("Asia/Kolkata", false).next("DL", 12L), "flag OFF: the id, as before");
    }

    @Test
    void unknownTypesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> service("Asia/Dubai", false).next("XX", 12L));
    }

    @Test
    void companyCodesAreSixBase36CharactersAndRandom() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = DocumentNumberService.randomCompanyCode();
            assertTrue(code.matches("[0-9A-Z]{6}"), code);
            seen.add(code);
        }
        assertTrue(seen.size() > 1990, "random, not derived from anything");
    }
}
