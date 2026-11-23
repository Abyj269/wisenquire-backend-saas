package com.leads.controller;

import com.leads.dto.common.AddNoteRequest;
import com.leads.dto.customer.*;
import com.leads.dto.lead.LeadDto;
import com.leads.model.entity.User;
import com.leads.model.enums.CustomerStatus;
import com.leads.service.AuthService;
import com.leads.service.CustomerService;
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
@RequestMapping("/customers")
@RequiredArgsConstructor
@Tag(name = "Customer Master", description = "Company-wide customer/contact records, lifecycle status, and their associated leads")
public class CustomerController {

    private final CustomerService customerService;
    private final LeadService leadService;
    private final AuthService authService;

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:EDIT')")
    @Operation(summary = "Update Customer", description = "Edits customer details; every changed field is recorded in the customer's change log.")
    public ResponseEntity<CustomerDto> updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerUpdateRequest req) {
        User currentUser = authService.getCurrentUser();
        return ResponseEntity.ok(customerService.updateCustomer(id, req, currentUser));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:VIEW')")
    @Operation(summary = "Get Customer by ID", description = "Full customer profile including every branch they have engaged with.")
    public ResponseEntity<CustomerDto> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomer(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:VIEW')")
    @Operation(summary = "Query Customers", description = "Paginated customer list filterable by name, mobile, branch (has-a-lead-there), and lifecycle status.")
    public ResponseEntity<Page<CustomerDto>> getCustomers(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(required = false) Boolean blocked,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "lastLeadAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return ResponseEntity.ok(customerService.listCustomers(companyId, branchId, name, mobile, status, fromDate, toDate, blocked, PageRequest.of(page, size, sort)));
    }

    @PutMapping("/{id}/unblock")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:EDIT')")
    @Operation(summary = "Unblock Customer", description = "Restores this customer's WhatsApp conversation to the inbox list.")
    public ResponseEntity<CustomerDto> unblockCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.unblockCustomer(id));
    }

    @GetMapping("/suggest")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:VIEW') or hasAuthority('LEADS:ADD') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Suggest Customers by Phone", description = "Typeahead used while entering a phone number during lead creation — matches ignore country code.")
    public ResponseEntity<List<CustomerSummaryDto>> suggest(@RequestParam Long companyId, @RequestParam String phone) {
        return ResponseEntity.ok(customerService.suggest(companyId, phone));
    }

    @GetMapping("/{id}/leads")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:VIEW')")
    @Operation(summary = "Get Customer's Leads", description = "Every lead for this customer across every branch — cross-branch history is intentional, not filtered.")
    public ResponseEntity<Page<LeadDto>> getCustomerLeads(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(leadService.getLeadsByCustomer(id, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:VIEW')")
    @Operation(summary = "Get Customer Change Log", description = "Audit trail of customer field edits and lead-linkage events.")
    public ResponseEntity<List<CustomerChangeLogDto>> getCustomerLogs(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getChangeLogs(id));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('CUSTOMERS:EDIT')")
    @Operation(summary = "Add Note to Customer", description = "Adds a free-text note, optionally with one small file attachment, to the customer's change log.")
    public ResponseEntity<CustomerChangeLogDto> addNote(@PathVariable Long id, @RequestBody AddNoteRequest req) {
        return ResponseEntity.ok(customerService.addNote(id, req.getNote(), req.getAttachmentName(), req.getAttachmentData()));
    }
}
