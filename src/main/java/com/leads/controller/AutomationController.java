package com.leads.controller;

import com.leads.dto.automation.*;
import com.leads.model.entity.User;
import com.leads.service.AuthService;
import com.leads.service.AutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/automations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "WhatsApp Automations", description = "Manage WhatsApp automation workflows, triggers, actions, and execution logs")
public class AutomationController {

    private final AutomationService automationService;
    private final AuthService authService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:VIEW')")
    @Operation(summary = "Get Automations List", description = "Returns paginated list of automations for a company, with optional branch and status filters.")
    public ResponseEntity<Page<AutomationListDto>> getAutomations(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(automationService.getAutomations(companyId, branchId, status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:VIEW')")
    @Operation(summary = "Get Automation Detail", description = "Returns single automation detail including trigger, conditions, and action definitions.")
    public ResponseEntity<AutomationDto> getAutomationById(@PathVariable Long id) {
        return ResponseEntity.ok(automationService.getAutomationById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:ADD')")
    @Operation(summary = "Create Automation", description = "Creates a new automation in DRAFT state.")
    public ResponseEntity<AutomationDto> createAutomation(@Valid @RequestBody AutomationCreateRequest req) {
        User currentUser = authService.getCurrentUser();
        Long userId = currentUser != null ? currentUser.getId() : null;
        return ResponseEntity.ok(automationService.createAutomation(req, userId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:EDIT')")
    @Operation(summary = "Update Automation", description = "Updates an existing automation's configuration.")
    public ResponseEntity<AutomationDto> updateAutomation(
            @PathVariable Long id,
            @Valid @RequestBody AutomationCreateRequest req) {
        return ResponseEntity.ok(automationService.updateAutomation(id, req));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:EDIT')")
    @Operation(summary = "Publish Automation", description = "Activates an automation for processing incoming messages.")
    public ResponseEntity<AutomationDto> publishAutomation(@PathVariable Long id) {
        return ResponseEntity.ok(automationService.publishAutomation(id));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:EDIT')")
    @Operation(summary = "Pause Automation", description = "Pauses an active automation.")
    public ResponseEntity<AutomationDto> pauseAutomation(@PathVariable Long id) {
        return ResponseEntity.ok(automationService.pauseAutomation(id));
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:ADD')")
    @Operation(summary = "Duplicate Automation", description = "Creates a copy of an existing automation as DRAFT.")
    public ResponseEntity<AutomationDto> duplicateAutomation(@PathVariable Long id) {
        User currentUser = authService.getCurrentUser();
        Long userId = currentUser != null ? currentUser.getId() : null;
        return ResponseEntity.ok(automationService.duplicateAutomation(id, userId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:DELETE')")
    @Operation(summary = "Delete Automation", description = "Soft deletes an automation.")
    public ResponseEntity<Void> deleteAutomation(@PathVariable Long id) {
        automationService.deleteAutomation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/executions")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:VIEW')")
    @Operation(summary = "Get Automation Executions", description = "Returns paginated execution logs for an automation.")
    public ResponseEntity<Page<AutomationExecutionDto>> getExecutions(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(automationService.getExecutions(id, pageable));
    }

    @GetMapping("/{id}/executions/{execId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:VIEW')")
    @Operation(summary = "Get Execution Detail", description = "Returns detailed step execution log for a single execution.")
    public ResponseEntity<AutomationExecutionDto> getExecution(
            @PathVariable Long id,
            @PathVariable Long execId) {
        return ResponseEntity.ok(automationService.getExecution(id, execId));
    }

    @GetMapping("/{id}/analytics")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_AUTOMATIONS:VIEW')")
    @Operation(summary = "Get Automation Analytics", description = "Returns execution counts and success rate metrics.")
    public ResponseEntity<AutomationAnalyticsDto> getAnalytics(@PathVariable Long id) {
        return ResponseEntity.ok(automationService.getAnalytics(id));
    }
}
