package com.leads.service;

import com.leads.model.entity.Company;
import org.springframework.stereotype.Component;

import java.time.*;

/**
 * D15 (v3.1): the only API for calendar decisions ("today", date filters, day/week/month buckets).
 * Delivered in Foundation B (01 Part B item 11); the design is owned by module 14.
 * <p>
 * The database holds {@link LocalDateTime} values in <b>server wall-clock</b> time, Asia/Dubai (+04).
 * A company's calendar is its own {@code timezone}: an Asia/Kolkata company is 90 minutes ahead,
 * so between 22:30 and 24:00 Dubai time it is already the next day there.
 * <ul>
 *   <li>{@link #now}, {@link #today}: the company's local time / date.</li>
 *   <li>{@link #range}: company-local dates {@code [from, to]} as server wall-clock
 *       {@code [start, endExclusive)}, for DB predicates.</li>
 *   <li>{@link #toServer} / {@link #fromServer}: convert one value.</li>
 *   <li>{@link #shiftMinutes}: company offset minus server offset (+90 / 0 / -60 for the 7 allowed
 *       zones), for SQL buckets {@code DATE(DATE_ADD(col, INTERVAL :shift MINUTE))}. Exact only for
 *       fixed-offset zones (all 7 allowed zones are); it uses the offsets at the current instant.</li>
 * </ul>
 * Conversions use real zone rules, so they stay correct for any zone a legacy company may still have.
 * A missing or invalid company timezone falls back to the server zone.
 */
@Component
public class CompanyClock {

    /** The server's wall-clock zone (container TZ, R20). DB LocalDateTime values are in this zone. */
    public static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Dubai");

    private final Clock clock;

    public CompanyClock(Clock clock) { this.clock = clock; }

    /** The company's local date-time now. */
    public LocalDateTime now(Company company) { return ZonedDateTime.now(clock).withZoneSameInstant(zone(company)).toLocalDateTime(); }

    /** The company's local date today. */
    public LocalDate today(Company company) { return now(company).toLocalDate(); }

    /** Server wall-clock now (what entity timestamps store). */
    public LocalDateTime serverNow() { return ZonedDateTime.now(clock).withZoneSameInstant(SERVER_ZONE).toLocalDateTime(); }

    /** Company-local days {@code from..to} (inclusive) as a server wall-clock half-open range. */
    public Range range(Company company, LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("A date range needs both a start and an end date.");
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        return new Range(toServer(company, from.atStartOfDay()), toServer(company, to.plusDays(1).atStartOfDay()));
    }

    /** A company-local date-time as server wall-clock. */
    public LocalDateTime toServer(Company company, LocalDateTime companyLocal) {
        return companyLocal == null ? null : companyLocal.atZone(zone(company)).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
    }

    /** A server wall-clock value as company-local time. */
    public LocalDateTime fromServer(Company company, LocalDateTime serverLocal) {
        return serverLocal == null ? null : serverLocal.atZone(SERVER_ZONE).withZoneSameInstant(zone(company)).toLocalDateTime();
    }

    /** Company UTC offset minus server UTC offset, in minutes, at the current instant. */
    public int shiftMinutes(Company company) {
        Instant now = clock.instant();
        return (zone(company).getRules().getOffset(now).getTotalSeconds() - SERVER_ZONE.getRules().getOffset(now).getTotalSeconds()) / 60;
    }

    public ZoneId zone(Company company) {
        String tz = company == null ? null : company.getTimezone();
        if (tz == null || tz.isBlank()) return SERVER_ZONE;
        try { return ZoneId.of(tz); } catch (DateTimeException e) { return SERVER_ZONE; }
    }

    /** Server wall-clock {@code [start, endExclusive)}: use {@code col >= start AND col < endExclusive}. */
    public record Range(LocalDateTime start, LocalDateTime endExclusive) { }
}
