package com.leads.controller;

import com.leads.dto.common.AddNoteRequest;
import com.leads.dto.lead.*;
import com.leads.service.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/leads")
@RequiredArgsConstructor
@Tag(name = "Leads Management", description = "Endpoints for creating, managing, assigning, and converting leads")
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:ADD')")
    @Operation(summary = "Create Lead", description = "Creates a direct or multichannel lead with dynamic fields and linked products.")
    public ResponseEntity<LeadDto> createLead(@Valid @RequestBody LeadCreateRequest req) {
        return ResponseEntity.ok(leadService.createLead(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Update Lead", description = "Updates lead details, status transitions, dynamic fields, and product line items.")
    public ResponseEntity<LeadDto> updateLead(@PathVariable Long id, @Valid @RequestBody LeadUpdateRequest req) {
        return ResponseEntity.ok(leadService.updateLead(id, req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Lead by ID", description = "Retrieves full 360° lead details including dynamic custom field values.")
    public ResponseEntity<LeadDto> getLeadById(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.getLeadById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Query Leads", description = "Filters leads by company, branch, status, source, with keyword search and pagination.")
    public ResponseEntity<Page<LeadDto>> getLeads(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long statusId,
            @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) com.leads.model.enums.PurposeClassification purposeClassification,
            @RequestParam(required = false) Boolean missingPurpose,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) Boolean excludeClosed,
            @RequestParam(required = false) Long assignedToUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return ResponseEntity.ok(leadService.getLeads(companyId, branchId, statusId, sourceId, search, fromDate, toDate, excludeClosed, purposeClassification, assignedToUserId, missingPurpose, PageRequest.of(page, size, sort)));
    }

    @PostMapping("/assign")
    // LEADS:EDIT lets an OWN agent self-claim (module 06 P1); assigning anyone else still needs LEADS:ASSIGN (checked in the service).
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:ASSIGN') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Assign or Reassign Leads", description = "Assigns one or multiple leads to an agent with activity audit log.")
    public ResponseEntity<Void> assignLeads(@Valid @RequestBody LeadAssignRequest req) {
        leadService.assignLeads(req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/convert")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:APPROVE') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Convert Lead", description = "Converts lead to customer/deal, updating stage to CONVERTED.")
    public ResponseEntity<LeadDto> convertLead(@Valid @RequestBody LeadConvertRequest req) {
        return ResponseEntity.ok(leadService.convertLead(req));
    }

    @PostMapping("/mark-lost")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Mark Lead Lost", description = "Marks a lead Lost/Disqualified with a required reason and note, updating stage to LOST.")
    public ResponseEntity<LeadDto> markLeadLost(@Valid @RequestBody LeadLostRequest req) {
        return ResponseEntity.ok(leadService.markLeadLost(req));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Lead Timeline Activities", description = "Returns chronological timeline and audit logs for the lead.")
    public ResponseEntity<List<LeadTimelineDto>> getLeadTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.getLeadTimeline(id));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Add Note to Lead", description = "Adds a free-text note, optionally with one small file attachment, to the lead's activity log.")
    public ResponseEntity<LeadTimelineDto> addNote(@PathVariable Long id, @RequestBody AddNoteRequest req) {
        return ResponseEntity.ok(leadService.addNote(id, req.getNote(), req.getAttachmentName(), req.getAttachmentData()));
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Company Lead Metrics", description = "Returns total leads, conversion rates, and stage/source breakdowns, optionally scoped to a branch and/or date range.")
    public ResponseEntity<LeadMetricsDto> getCompanyMetrics(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate
    ) {
        return ResponseEntity.ok(leadService.getCompanyMetrics(companyId, branchId, fromDate, toDate));
    }
}
