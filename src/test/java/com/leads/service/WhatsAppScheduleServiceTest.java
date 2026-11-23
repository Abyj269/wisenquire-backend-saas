package com.leads.service;

import com.leads.model.entity.BranchWorkingHours;
import com.leads.dto.whatsapp.*;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class WhatsAppScheduleServiceTest {
    @Test void overnightShiftEndsNextDayAtExclusiveBoundary() {
        var shift = BranchWorkingHours.builder().isOpen(true).openTime(LocalTime.of(22, 0)).closeTime(LocalTime.of(6, 0)).build();
        assertTrue(WhatsAppScheduleService.withinShift(shift, LocalTime.of(23, 0), false));
        assertTrue(WhatsAppScheduleService.withinShift(shift, LocalTime.of(5, 59), true));
        assertFalse(WhatsAppScheduleService.withinShift(shift, LocalTime.of(6, 0), true));
        assertFalse(WhatsAppScheduleService.withinShift(shift, LocalTime.NOON, false));
    }
    @Test void rejectsDuplicateWeekdaysAndAmbiguousOpenIntervals() {
        var days = IntStream.rangeClosed(1, 7).mapToObj(i -> WorkingHoursDayDto.builder()
                .dayOfWeek(i).isOpen(true).openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(17, 0)).build()).toList();
        var req = WorkingHoursUpsertRequest.builder().branchIds(List.of(1L)).days(days).build();
        assertDoesNotThrow(() -> WhatsAppScheduleService.validateSchedule(req));
        days.get(6).setDayOfWeek(1);
        assertThrows(IllegalArgumentException.class, () -> WhatsAppScheduleService.validateSchedule(req));
        days.get(6).setDayOfWeek(7);
        days.get(0).setCloseTime(LocalTime.of(9, 0));
        assertThrows(IllegalArgumentException.class, () -> WhatsAppScheduleService.validateSchedule(req));
    }
}
