package com.leads.controller;

import com.leads.dto.broadcast.BroadcastCreateRequest;
import com.leads.dto.broadcast.BroadcastDto;
import com.leads.dto.broadcast.BroadcastRecipientDto;
import com.leads.service.BroadcastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/broadcasts")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Broadcast Messaging", description = "Bulk WhatsApp template campaign management, recipient logs, and real-time delivery tracking")
public class BroadcastController {

    private final BroadcastService broadcastService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:ADD')")
    @Operation(summary = "Create a new Broadcast Campaign")
    public ResponseEntity<BroadcastDto> createBroadcast(@Valid @RequestBody BroadcastCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(broadcastService.createBroadcast(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:VIEW')")
    @Operation(summary = "Get Broadcast Campaign by ID")
    public ResponseEntity<BroadcastDto> getBroadcastById(@PathVariable Long id) {
        return ResponseEntity.ok(broadcastService.getBroadcastById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:VIEW')")
    @Operation(summary = "Get Page of Broadcast Campaigns")
    public ResponseEntity<Page<BroadcastDto>> getBroadcasts(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(broadcastService.getBroadcasts(companyId, branchId, pageable));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:SEND')")
    @Operation(summary = "Trigger Async Sending of Broadcast Campaign")
    public ResponseEntity<BroadcastDto> sendBroadcast(@PathVariable Long id) {
        return ResponseEntity.ok(broadcastService.sendBroadcast(id));
    }

    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:VIEW')")
    @Operation(summary = "Get Delivery Logs for a Broadcast Campaign")
    public ResponseEntity<Page<BroadcastRecipientDto>> getBroadcastLogs(
            @PathVariable Long id,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(broadcastService.getBroadcastLogs(id, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('BROADCASTS:DELETE')")
    @Operation(summary = "Soft Delete a Broadcast Campaign")
    public ResponseEntity<Void> deleteBroadcast(@PathVariable Long id) {
        broadcastService.deleteBroadcast(id);
        return ResponseEntity.noContent().build();
    }
}
