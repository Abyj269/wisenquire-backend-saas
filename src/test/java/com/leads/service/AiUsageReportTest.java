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
    AiUsageReportController controller = new AiUsageReportController(access,jdbc);
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
        when(access.branchIds()).thenReturn(List.of(2L,3L));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Collections.singletonMap("firstRecordedAt",null)));
        when(jdbc.queryForMap(anyString(),anyMap())).thenReturn(Map.of());
        controller.report(1L,2L,LocalDate.of(2026,9,1),LocalDate.of(2026,9,18));
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(Map.class);
        verify(jdbc,times(2)).queryForMap(sql.capture(),params.capture());
        assertTrue(sql.getAllValues().get(0).contains("branch_id IN (:branches)"));
        assertTrue(sql.getAllValues().get(0).contains("branch_id=:branch"));
        assertTrue(sql.getAllValues().get(0).contains("company_id=:company"));
        assertEquals(Timestamp.valueOf("2026-09-19 00:00:00"),params.getValue().get("to"));
    }
}
