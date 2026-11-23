package com.leads.controller;

import com.leads.dto.whatsapp.*;
import com.leads.service.WhatsAppScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/whatsapp")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Business Hours & Holidays", description = "Branch-wise working hours, the company holiday calendar, and their auto-reply messages")
public class WhatsAppScheduleController {

    private final WhatsAppScheduleService scheduleService;

    @GetMapping("/business-hours")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Get a branch's weekly working-hours schedule")
    public ResponseEntity<List<WorkingHoursDayDto>> getWorkingHours(@RequestParam Long branchId) {
        return ResponseEntity.ok(scheduleService.getWorkingHours(branchId));
    }

    @PutMapping("/business-hours")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Save a weekly working-hours schedule to one or more branches at once")
    public ResponseEntity<Void> saveWorkingHours(@Valid @RequestBody WorkingHoursUpsertRequest req) {
        scheduleService.saveWorkingHours(req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "List a company's holidays (irrespective of branch — each carries its own applicable branch list)")
    public ResponseEntity<List<WhatsAppHolidayDto>> listHolidays(@RequestParam Long companyId) {
        return ResponseEntity.ok(scheduleService.listHolidays(companyId));
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:ADD')")
    @Operation(summary = "Create a date-range holiday for one or more branches")
    public ResponseEntity<WhatsAppHolidayDto> createHoliday(@Valid @RequestBody WhatsAppHolidayUpsertRequest req) {
        return ResponseEntity.ok(scheduleService.createHoliday(req));
    }

    @PutMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Edit an existing holiday")
    public ResponseEntity<WhatsAppHolidayDto> updateHoliday(@PathVariable Long id, @Valid @RequestBody WhatsAppHolidayUpsertRequest req) {
        return ResponseEntity.ok(scheduleService.updateHoliday(id, req));
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:DELETE')")
    @Operation(summary = "Delete a holiday")
    public ResponseEntity<Void> deleteHoliday(@PathVariable Long id) {
        scheduleService.deleteHoliday(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auto-reply-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Get the company's working-hours/non-working-hours/holiday common auto-reply messages")
    public ResponseEntity<WhatsAppAutoReplySettingsDto> getAutoReplySettings(@RequestParam Long companyId) {
        return ResponseEntity.ok(scheduleService.getAutoReplySettings(companyId));
    }

    @PutMapping("/auto-reply-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Save the company's common auto-reply messages")
    public ResponseEntity<WhatsAppAutoReplySettingsDto> saveAutoReplySettings(@RequestParam Long companyId, @RequestBody WhatsAppAutoReplySettingsUpsertRequest req) {
        return ResponseEntity.ok(scheduleService.saveAutoReplySettings(companyId, req));
    }
}
