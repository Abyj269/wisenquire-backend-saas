package com.leads.controller;

import com.leads.dto.delivery.CompanyDeliverySettingsDto;
import com.leads.dto.delivery.DeliveryFailureReasonDto;
import com.leads.service.DeliverySettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
@Tag(name = "Delivery Settings", description = "Endpoints for the Delivery module's company-level toggle and failure-reason master list")
public class DeliverySettingsController {

    private final DeliverySettingsService settingsService;

    // --- Delivery Failure Reasons ---
    @GetMapping("/delivery-failure-reasons")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Get Delivery Failure Reasons by Company")
    public ResponseEntity<List<DeliveryFailureReasonDto>> getFailureReasons(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getFailureReasonsByCompany(companyId));
    }

    @PostMapping("/delivery-failure-reasons")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Create Delivery Failure Reason")
    public ResponseEntity<DeliveryFailureReasonDto> createFailureReason(@RequestParam Long companyId, @Valid @RequestBody DeliveryFailureReasonDto dto) {
        return ResponseEntity.ok(settingsService.createFailureReason(companyId, dto));
    }

    @PutMapping("/delivery-failure-reasons/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Update Delivery Failure Reason")
    public ResponseEntity<DeliveryFailureReasonDto> updateFailureReason(@PathVariable Long id, @Valid @RequestBody DeliveryFailureReasonDto dto) {
        return ResponseEntity.ok(settingsService.updateFailureReason(id, dto));
    }

    @DeleteMapping("/delivery-failure-reasons/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Delete Delivery Failure Reason")
    public ResponseEntity<Void> deleteFailureReason(@PathVariable Long id) {
        settingsService.deleteFailureReason(id);
        return ResponseEntity.ok().build();
    }

    // --- Company Delivery Settings ---
    @GetMapping("/delivery-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:VIEW')")
    @Operation(summary = "Get Company Delivery Settings", description = "Retrieves the Delivery module's enable toggle and approval-requirement flags.")
    public ResponseEntity<CompanyDeliverySettingsDto> getCompanyDeliverySettings(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getCompanyDeliverySettings(companyId));
    }

    @PutMapping("/delivery-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('DELIVERIES:APPROVE')")
    @Operation(summary = "Update Company Delivery Settings", description = "Toggles the Delivery module on/off and its approval-requirement flags.")
    public ResponseEntity<CompanyDeliverySettingsDto> updateCompanyDeliverySettings(@RequestParam Long companyId, @RequestBody CompanyDeliverySettingsDto dto) {
        return ResponseEntity.ok(settingsService.updateCompanyDeliverySettings(companyId, dto));
    }
}
