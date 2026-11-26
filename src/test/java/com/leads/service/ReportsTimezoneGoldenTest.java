package com.leads.service;

import com.leads.dto.reports.CustomerSummaryDto;
import com.leads.dto.reports.TrendPointDto;
import com.leads.model.entity.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Module 10 P1 (D15, v3.1) golden timezone tests for the in-memory report path: stored times are
 * server (Asia/Dubai) wall-clock, buckets are company-local days, weeks and months.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportsTimezoneGoldenTest {

    @Mock TenantAccess access;
    @Spy CompanyClock companyClock = new CompanyClock(Clock.system(CompanyClock.SERVER_ZONE));
    @InjectMocks ReportsService reports;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private static Company in(String zone) { return Company.builder().id(1L).timezone(zone).build(); }

    private static List<CustomerSummaryDto> at(String... serverTimes) {
        return java.util.Arrays.stream(serverTimes).map(t -> CustomerSummaryDto.builder().referenceDate(LocalDateTime.parse(t)).build()).toList();
    }

    private List<Long> daily(Company company, List<CustomerSummaryDto> acquired, String from, String to) {
        return reports.buildTrendBuckets(company, acquired, List.of(), List.of(), LocalDate.parse(from), LocalDate.parse(to), 1, ChronoUnit.DAYS, DAY)
                .stream().map(TrendPointDto::getAcquired).toList();
    }

    @Test
    void kolkataLeadsAt2229And2231DubaiFallOnDifferentIstDays() {
        // 22:29 Dubai = 23:59 IST on the 10th; 22:31 Dubai = 00:01 IST on the 11th.
        assertEquals(List.of(1L, 1L), daily(in("Asia/Kolkata"), at("2026-03-10T22:29:00", "2026-03-10T22:31:00"), "2026-03-10", "2026-03-11"));
    }

    @Test
    void dubaiCompaniesAreUnchanged() {
        assertEquals(List.of(2L, 0L), daily(in("Asia/Dubai"), at("2026-03-10T22:29:00", "2026-03-10T22:31:00"), "2026-03-10", "2026-03-11"));
    }

    @Test
    void riyadhLeadsAt2330And0030DubaiAreTheSameRiyadhDay() {
        // 23:30 Dubai = 22:30 Riyadh on the 10th; 00:30 Dubai on the 11th = 23:30 Riyadh on the 10th.
        assertEquals(List.of(2L, 0L), daily(in("Asia/Riyadh"), at("2026-03-10T23:30:00", "2026-03-11T00:30:00"), "2026-03-10", "2026-03-11"));
    }

    @Test
    void the31MarchAt2230DubaiBelongsToAprilInIndia() {
        // 22:30 Dubai on 31 March = 00:00 IST on 1 April (also the Indian financial-year start).
        List<TrendPointDto> months = reports.buildTrendBuckets(in("Asia/Kolkata"), at("2026-03-31T22:30:00"), List.of(), List.of(),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"), 1, ChronoUnit.MONTHS, DateTimeFormatter.ofPattern("MMM yyyy"));
        assertEquals("Mar 2026", months.get(0).getLabel());
        assertEquals(List.of(0L, 1L), months.stream().map(TrendPointDto::getAcquired).toList());
    }

    @Test
    void hourlyBucketsAreCompanyHoursWithCompanyLabels() {
        // 22:31 Dubai = 00:01 IST: the first IST hour of the 11th.
        List<TrendPointDto> hours = reports.buildHourlyTrendBuckets(in("Asia/Kolkata"), at("2026-03-10T22:31:00"), List.of(), List.of(), LocalDate.parse("2026-03-11"));
        assertEquals(24, hours.size());
        assertEquals("12 AM", hours.get(0).getLabel());
        assertEquals(1L, hours.get(0).getAcquired());
    }
}
