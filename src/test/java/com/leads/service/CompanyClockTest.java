package com.leads.service;

import com.leads.model.entity.Company;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/** 01 §7 "CompanyClock (v3.1)" plus the D15 golden cases. The server wall-clock is Asia/Dubai. */
class CompanyClockTest {

    private static Company company(String tz) { return Company.builder().name("x").code("x").timezone(tz).build(); }

    /** A clock fixed at the given Dubai (server) wall-clock time. */
    private static CompanyClock at(LocalDateTime serverTime) {
        return new CompanyClock(Clock.fixed(serverTime.atZone(CompanyClock.SERVER_ZONE).toInstant(), CompanyClock.SERVER_ZONE));
    }

    private final Company kolkata = company("Asia/Kolkata"), dubai = company("Asia/Dubai"), muscat = company("Asia/Muscat"),
            riyadh = company("Asia/Riyadh"), qatar = company("Asia/Qatar"), bahrain = company("Asia/Bahrain"), kuwait = company("Asia/Kuwait");

    @Test
    void shiftMinutesForTheThreeOffsetGroups() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 24, 12, 0));
        assertEquals(90, clock.shiftMinutes(kolkata));
        assertEquals(0, clock.shiftMinutes(dubai));
        assertEquals(0, clock.shiftMinutes(muscat));
        for (Company c : new Company[]{riyadh, qatar, bahrain, kuwait}) assertEquals(-60, clock.shiftMinutes(c), c.getTimezone());
    }

    @Test
    void kolkataDayIsServer2230ToServer2230() {
        LocalDate d = LocalDate.of(2026, 11, 24);
        CompanyClock.Range r = at(LocalDateTime.of(2026, 11, 24, 12, 0)).range(kolkata, d, d);
        assertEquals(LocalDateTime.of(2026, 11, 23, 22, 30), r.start());
        assertEquals(LocalDateTime.of(2026, 11, 24, 22, 30), r.endExclusive());
    }

    @Test
    void kolkataTodayAtServer2245IsTheNextDay() {
        assertEquals(LocalDate.of(2026, 11, 25), at(LocalDateTime.of(2026, 11, 24, 22, 45)).today(kolkata));
        assertEquals(LocalDate.of(2026, 11, 24), at(LocalDateTime.of(2026, 11, 24, 22, 29)).today(kolkata));
        assertEquals(LocalDate.of(2026, 11, 24), at(LocalDateTime.of(2026, 11, 24, 22, 45)).today(dubai));
    }

    @Test
    void goldenIstMidnightSplitsServer2229And2231IntoDifferentDays() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 25, 9, 0));
        LocalDate d = LocalDate.of(2026, 11, 25);
        CompanyClock.Range today = clock.range(kolkata, d, d);
        LocalDateTime before = LocalDateTime.of(2026, 11, 24, 22, 29), after = LocalDateTime.of(2026, 11, 24, 22, 31);
        assertFalse(inside(today, before), "23:59 IST belongs to the previous day");
        assertTrue(inside(today, after), "00:01 IST belongs to today");
        assertEquals(LocalDate.of(2026, 11, 24), clock.fromServer(kolkata, before).toLocalDate());
        assertEquals(LocalDate.of(2026, 11, 25), clock.fromServer(kolkata, after).toLocalDate());
    }

    @Test
    void goldenRiyadhKeepsServer2330And0030OnTheSameDay() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 25, 9, 0));
        assertEquals(LocalDateTime.of(2026, 11, 24, 22, 30), clock.fromServer(riyadh, LocalDateTime.of(2026, 11, 24, 23, 30)));
        assertEquals(LocalDateTime.of(2026, 11, 24, 23, 30), clock.fromServer(riyadh, LocalDateTime.of(2026, 11, 25, 0, 30)));
    }

    @Test
    void golden31MarchServer2230IsTheFirstOfAprilInIndia() {
        LocalDateTime server = LocalDateTime.of(2027, 3, 31, 22, 30);
        assertEquals(LocalDateTime.of(2027, 4, 1, 0, 0), at(server).fromServer(kolkata, server));
        assertEquals(LocalDate.of(2027, 4, 1), at(server).today(kolkata), "new month and new Indian financial year");
    }

    @Test
    void goldenDeliveryTodayAt0030IstIsNotInThePast() {
        // 00:30 IST = 23:00 Dubai on the previous server date.
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 24, 23, 0));
        assertEquals(LocalDate.of(2026, 11, 25), clock.today(kolkata));
    }

    @Test
    void toServerAndFromServerRoundTrip() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 24, 12, 0));
        LocalDateTime local = LocalDateTime.of(2026, 11, 24, 9, 15);
        for (Company c : new Company[]{kolkata, dubai, riyadh}) assertEquals(local, clock.fromServer(c, clock.toServer(c, local)), c.getTimezone());
        assertEquals(LocalDateTime.of(2026, 11, 24, 7, 45), clock.toServer(kolkata, local));
    }

    @Test
    void missingOrInvalidZoneFallsBackToTheServerZone() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 24, 12, 0));
        assertEquals(CompanyClock.SERVER_ZONE, clock.zone(company(null)));
        assertEquals(CompanyClock.SERVER_ZONE, clock.zone(company("Not/AZone")));
        assertEquals(0, clock.shiftMinutes(company("")));
    }

    @Test
    void rangeRejectsBadInput() {
        CompanyClock clock = at(LocalDateTime.of(2026, 11, 24, 12, 0));
        assertThrows(IllegalArgumentException.class, () -> clock.range(dubai, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> clock.range(dubai, null, LocalDate.of(2026, 1, 1)));
    }

    private static boolean inside(CompanyClock.Range r, LocalDateTime t) { return !t.isBefore(r.start()) && t.isBefore(r.endExclusive()); }
}
