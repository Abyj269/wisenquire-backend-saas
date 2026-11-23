package com.leads.controller;

import com.leads.dto.delivery.*;
import com.leads.dto.lead.LeadTimelineDto;
import com.leads.model.enums.DeliveryStatus;
import com.leads.service.DeliveryService;
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

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/deliveries")
@RequiredArgsConstructor
@Tag(name = "Delivery Management", description = "Endpoints for scheduling, assigning, and tracking deliveries against confirmed Lead orders")
public class DeliveryController {

    private final DeliveryService deliveryService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:ADD')")
    @Operation(summary = "Schedule Delivery", description = "Schedules a delivery for a Lead's confirmed order on a given date.")
    public ResponseEntity<DeliveryDto> createDelivery(@Valid @RequestBody DeliveryCreateRequest req) {
        return ResponseEntity.ok(deliveryService.createDelivery(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Update Delivery", description = "Edits a delivery's recipient/address/date/assignment while it is still Scheduled.")
    public ResponseEntity<DeliveryDto> updateDelivery(@PathVariable Long id, @Valid @RequestBody DeliveryUpdateRequest req) {
        return ResponseEntity.ok(deliveryService.updateDelivery(id, req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Get Delivery by ID")
    public ResponseEntity<DeliveryDto> getDeliveryById(@PathVariable Long id) {
        return ResponseEntity.ok(deliveryService.getDeliveryById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Query Deliveries", description = "Filters deliveries by company, branch, status, assignee, lead, and scheduled date — powers the day view, admin grid, and approvals queue.")
    public ResponseEntity<Page<DeliveryDto>> getDeliveries(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) Long assignedToUserId,
            @RequestParam(required = false) Long leadId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate scheduledDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate scheduledDateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate scheduledDateTo,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "scheduledDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        return ResponseEntity.ok(deliveryService.getDeliveries(companyId, branchId, status, assignedToUserId, leadId,
                scheduledDate, scheduledDateFrom, scheduledDateTo, unassigned, search, PageRequest.of(page, size, sort)));
    }

    @GetMapping("/unassigned-today-count")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Count Unassigned Deliveries Due Today", description = "Powers the dashboard's 'needs a delivery agent' warning — excludes already-terminal deliveries.")
    public ResponseEntity<Long> countUnassignedToday(@RequestParam(required = false) Long companyId, @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(deliveryService.countUnassignedToday(companyId, branchId));
    }

    @PostMapping("/assign")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:ASSIGN')")
    @Operation(summary = "Assign or Reassign Deliveries", description = "Assigns one or multiple deliveries to a delivery agent.")
    public ResponseEntity<Void> assignDeliveries(@Valid @RequestBody DeliveryAssignRequest req) {
        deliveryService.assignDeliveries(req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/out-for-delivery")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Mark Out for Delivery", description = "Optional step marking that the agent has departed with the item.")
    public ResponseEntity<DeliveryDto> markOutForDelivery(@PathVariable Long id) {
        return ResponseEntity.ok(deliveryService.markOutForDelivery(id));
    }

    @PostMapping("/{id}/mark-delivered")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Mark Delivered", description = "Marks the delivery successfully completed, with optional text proof notes.")
    public ResponseEntity<DeliveryDto> markDelivered(@PathVariable Long id, @RequestBody(required = false) DeliveryMarkDeliveredRequest req) {
        return ResponseEntity.ok(deliveryService.markDelivered(id, req != null ? req : DeliveryMarkDeliveredRequest.builder().build()));
    }

    @PostMapping("/{id}/mark-not-delivered")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Mark Not Delivered", description = "Records a failed delivery attempt with a required reason.")
    public ResponseEntity<DeliveryDto> markNotDelivered(@PathVariable Long id, @Valid @RequestBody DeliveryMarkNotDeliveredRequest req) {
        return ResponseEntity.ok(deliveryService.markNotDelivered(id, req));
    }

    @PostMapping("/{id}/request-postpone")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Request Postponement", description = "Requests a new delivery date after a failed attempt — applies immediately or awaits approval per company settings.")
    public ResponseEntity<DeliveryDto> requestPostpone(@PathVariable Long id, @Valid @RequestBody DeliveryPostponeRequest req) {
        return ResponseEntity.ok(deliveryService.requestPostpone(id, req));
    }

    @PostMapping("/{id}/request-close")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:EDIT')")
    @Operation(summary = "Request Close", description = "Requests giving up on the delivery after a failed attempt — applies immediately or awaits approval per company settings.")
    public ResponseEntity<DeliveryDto> requestClose(@PathVariable Long id, @Valid @RequestBody DeliveryCloseRequest req) {
        return ResponseEntity.ok(deliveryService.requestClose(id, req));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Approve Pending Request", description = "Approves a pending postpone/close request.")
    public ResponseEntity<DeliveryDto> approve(@PathVariable Long id) {
        return ResponseEntity.ok(deliveryService.approve(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Reject Pending Request", description = "Rejects a pending postpone/close request, sending it back to Not Delivered for the agent to decide again.")
    public ResponseEntity<DeliveryDto> reject(@PathVariable Long id, @Valid @RequestBody DeliveryRejectRequest req) {
        return ResponseEntity.ok(deliveryService.reject(id, req));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Cancel Delivery", description = "Directly voids a delivery in any non-terminal state, e.g. the customer cancelled the order.")
    public ResponseEntity<DeliveryDto> cancel(@PathVariable Long id, @Valid @RequestBody DeliveryCancelRequest req) {
        return ResponseEntity.ok(deliveryService.cancel(id, req));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Get Delivery Timeline", description = "Returns chronological activity log for the delivery (also visible on the parent Lead's timeline).")
    public ResponseEntity<List<LeadTimelineDto>> getDeliveryTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(deliveryService.getDeliveryTimeline(id));
    }
}
