package com.leads.service;

import com.leads.model.entity.Branch;
import com.leads.model.entity.BranchWorkingHours;
import com.leads.model.entity.Company;
import com.leads.model.entity.WhatsAppAutoReplySettings;
import com.leads.model.entity.WhatsAppHoliday;
import com.leads.repository.BranchRepository;
import com.leads.repository.BranchWorkingHoursRepository;
import com.leads.repository.CompanyRepository;
import com.leads.repository.WhatsAppAutoReplySettingsRepository;
import com.leads.repository.WhatsAppHolidayRepository;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Module 14 P1 (D15): working hours, holidays and the once-per-day auto-reply follow the company's
 * own timezone, never the server's (Asia/Dubai). Fixed clocks; dates are 2026 (11 March is a Wednesday).
 */
class WhatsAppScheduleClockTest {

    private final BranchWorkingHoursRepository hours = mock(BranchWorkingHoursRepository.class);
    private final WhatsAppHolidayRepository holidays = mock(WhatsAppHolidayRepository.class);
    private final WhatsAppAutoReplySettingsRepository settings = mock(WhatsAppAutoReplySettingsRepository.class);

    private WhatsAppScheduleService at(String utcInstant) {
        CompanyClock clock = new CompanyClock(Clock.fixed(Instant.parse(utcInstant), CompanyClock.SERVER_ZONE));
        return new WhatsAppScheduleService(mock(TenantAccess.class), clock, hours, holidays, settings,
                mock(BranchRepository.class), mock(CompanyRepository.class));
    }

    private static Branch branch(String zone) {
        Company company = Company.builder().id(7L).timezone(zone).build();
        return Branch.builder().id(1L).company(company).build();
    }

    private void open(DayOfWeek day, int fromHour, int fromMinute, int toHour, int toMinute) {
        when(hours.findByBranchIdAndDayOfWeek(1L, day.getValue())).thenReturn(Optional.of(BranchWorkingHours.builder()
                .dayOfWeek(day.getValue()).isOpen(true).openTime(LocalTime.of(fromHour, fromMinute)).closeTime(LocalTime.of(toHour, toMinute)).build()));
    }

    @Test
    void riyadhOpensAt0800Local() {
        open(DayOfWeek.WEDNESDAY, 8, 0, 17, 0);
        assertFalse(at("2026-03-11T04:59:00Z").isWithinWorkingHours(branch("Asia/Riyadh")), "07:59 Riyadh (08:59 Dubai) is before opening");
        assertTrue(at("2026-03-11T05:00:00Z").isWithinWorkingHours(branch("Asia/Riyadh")), "08:00 Riyadh is open");
    }

    @Test
    void riyadhAt0030LocalUsesItsOwnTime() {
        open(DayOfWeek.WEDNESDAY, 1, 0, 10, 0);
        // 00:30 Riyadh on 1 July is 01:30 in Dubai: the server's clock would say the branch is open.
        assertFalse(at("2026-06-30T21:30:00Z").isWithinWorkingHours(branch("Asia/Riyadh")), "00:30 Riyadh is before a 01:00 opening");
        assertTrue(at("2026-06-30T22:00:00Z").isWithinWorkingHours(branch("Asia/Riyadh")), "01:00 Riyadh is open");
    }

    @Test
    void kolkataOpensAt0800IstWhichIs0630OnTheServer() {
        open(DayOfWeek.WEDNESDAY, 8, 0, 17, 0);
        when(settings.findByCompanyId(7L)).thenReturn(Optional.of(WhatsAppAutoReplySettings.builder()
                .workingHoursEnabled(true).workingHoursMessage("open").nonWorkingHoursEnabled(true).nonWorkingHoursMessage("closed").build()));
        assertEquals("closed", at("2026-03-11T02:29:00Z").resolveAutoReply(branch("Asia/Kolkata")).orElseThrow().text(), "07:59 IST");
        assertEquals("open", at("2026-03-11T02:30:00Z").resolveAutoReply(branch("Asia/Kolkata")).orElseThrow().text(), "08:00 IST = 06:30 Dubai");
    }

    @Test
    void kolkataAt0030IstIsAlreadyTheNextDay() {
        when(holidays.findActiveForBranch(eq(1L), eq(LocalDate.of(2026, 3, 12)))).thenReturn(List.of(WhatsAppHoliday.builder().messageText("holiday").build()));
        open(DayOfWeek.WEDNESDAY, 0, 0, 23, 59);
        // 00:30 IST on Thursday the 12th is still Wednesday 23:00 in Dubai.
        assertFalse(at("2026-03-11T19:00:00Z").isWithinWorkingHours(branch("Asia/Kolkata")), "the 12th's holiday applies");
        assertTrue(at("2026-03-11T19:00:00Z").isWithinWorkingHours(branch("Asia/Dubai")), "a Dubai company is still on Wednesday");
        assertEquals("holiday", at("2026-03-11T19:00:00Z").resolveAutoReply(branch("Asia/Kolkata")).orElseThrow().text());
    }

    @Test
    void kolkataAt2330IstIsStillTheSameDay() {
        when(holidays.findActiveForBranch(eq(1L), eq(LocalDate.of(2026, 3, 12)))).thenReturn(List.of(WhatsAppHoliday.builder().messageText("holiday").build()));
        open(DayOfWeek.WEDNESDAY, 9, 0, 23, 45);
        assertTrue(at("2026-03-11T18:00:00Z").isWithinWorkingHours(branch("Asia/Kolkata")), "23:30 IST Wednesday: open, tomorrow's holiday not yet");
        assertFalse(at("2026-03-11T18:20:00Z").isWithinWorkingHours(branch("Asia/Kolkata")), "23:50 IST: closed");
    }

    @Test
    void theOncePerDayAutoReplyResetsAtIstMidnight() {
        Company india = branch("Asia/Kolkata").getCompany();
        WhatsAppScheduleService midnight = at("2026-03-11T18:30:00Z"); // 00:00 IST on the 12th
        assertFalse(midnight.repliedToday(india, LocalDateTime.of(2026, 3, 11, 22, 29)), "22:29 Dubai = 23:59 IST on the 11th: yesterday");
        assertTrue(midnight.repliedToday(india, LocalDateTime.of(2026, 3, 11, 22, 30)), "22:30 Dubai = 00:00 IST on the 12th: today");
        assertFalse(midnight.repliedToday(india, null));
        WhatsAppScheduleService lateEvening = at("2026-03-11T18:29:00Z"); // 23:59 IST on the 11th
        assertTrue(lateEvening.repliedToday(india, LocalDateTime.of(2026, 3, 11, 4, 0)), "08:30 IST on the 11th: still today");
        assertEquals(LocalDateTime.of(2026, 3, 11, 22, 29), lateEvening.autoReplyStampNow(), "stored as server wall-clock");
    }
}
