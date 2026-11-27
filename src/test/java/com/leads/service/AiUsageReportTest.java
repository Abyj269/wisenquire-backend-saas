package com.leads.service;
import com.leads.controller.AiUsageReportController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class AiUsageReportTest {
    TenantAccess access = mock(TenantAccess.class);
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    CompanyClock clock = new CompanyClock(java.time.Clock.systemUTC());
    AiUsageReportController controller = new AiUsageReportController(access,jdbc,clock);
    com.leads.model.entity.Company company(String zone) { return com.leads.model.entity.Company.builder().id(1L).timezone(zone).build(); }
    @Test void deniedScopeCannotReadUsage() {
        doThrow(new AccessDeniedException("Denied")).when(access).scope(1L,2L);
        assertThrows(AccessDeniedException.class,()->controller.report(1L,2L,LocalDate.now(),LocalDate.now()));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsReversedAndExcessiveDateRanges() {
        assertThrows(IllegalArgumentException.class,()->controller.report(1L,null,LocalDate.of(2026,2,1),LocalDate.of(2026,1,1)));
        assertThrows(IllegalArgumentException.class,()->controller.report(1L,null,LocalDate.of(2024,1,1),LocalDate.of(2026,1,1)));
        verifyNoInteractions(jdbc);
    }
    @Test @SuppressWarnings("unchecked") void reportAppliesBranchRestrictionsAndInclusiveEndDate() {
        when(access.company(1L)).thenReturn(company("Asia/Dubai"));
        when(access.branchIds()).thenReturn(List.of(2L,3L));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Collections.singletonMap("firstRecordedAt",null)));
        when(jdbc.queryForMap(anyString(),anyMap())).thenReturn(Map.of());
        controller.report(1L,2L,LocalDate.of(2026,9,1),LocalDate.of(2026,9,18));
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(Map.class);
        verify(jdbc,times(2)).queryForMap(sql.capture(),params.capture());
        assertTrue(sql.getAllValues().get(0).contains("branch_id IN (:branches)"));
        assertTrue(sql.getAllValues().get(0).contains("branch_id=:branch"));
        assertTrue(sql.getAllValues().get(0).contains("company_id=:company"));
        // T-2 (module 18 part 2): the end of the 18th in Dubai is 2026-09-18 20:00 UTC (started_at is UTC).
        assertEquals(Timestamp.valueOf("2026-09-18 20:00:00"),params.getValue().get("to"));
        assertEquals(Timestamp.valueOf("2026-08-31 20:00:00"),params.getValue().get("from"));
        assertEquals(240, params.getValue().get("offset"), "rows are bucketed into the Dubai day");
    }
    @Test @SuppressWarnings("unchecked") void anIstCompanyGetsItsOwnDaysAndTimezone() {
        when(access.company(1L)).thenReturn(company("Asia/Kolkata"));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Collections.singletonMap("firstRecordedAt",Timestamp.valueOf("2026-09-01 00:00:00"))));
        when(jdbc.queryForMap(anyString(),anyMap())).thenReturn(Map.of());
        var result = controller.report(1L,null,LocalDate.of(2026,9,1),LocalDate.of(2026,9,1));
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(Map.class);
        verify(jdbc,times(2)).queryForList(sql.capture(),params.capture());
        assertEquals(Timestamp.valueOf("2026-08-31 18:30:00"),params.getValue().get("from"), "00:00 IST on the 1st");
        assertEquals(Timestamp.valueOf("2026-09-01 18:30:00"),params.getValue().get("to"));
        assertEquals(330, params.getValue().get("offset"));
        assertTrue(sql.getAllValues().get(1).contains("DATE(DATE_ADD(started_at, INTERVAL :offset MINUTE))"));
        assertEquals("Asia/Kolkata", result.get("timezone"));
        assertEquals(java.time.LocalDateTime.of(2026,9,1,4,0), result.get("firstRecordedAt"), "UTC midnight shown as server (Dubai) time");
    }
}
