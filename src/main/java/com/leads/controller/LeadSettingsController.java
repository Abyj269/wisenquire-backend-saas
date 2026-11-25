package com.leads.controller;

import com.leads.dto.settings.CompanyLeadSettingsDto;
import com.leads.dto.settings.DynamicFieldDto;
import com.leads.dto.settings.LeadLostReasonDto;
import com.leads.dto.settings.LeadPurposeDto;
import com.leads.dto.settings.LeadSourceDto;
import com.leads.dto.settings.LeadStatusDto;
import com.leads.dto.settings.PredefinedFieldConfigDto;
import com.leads.service.DynamicFieldService;
import com.leads.service.LeadSettingsService;
import com.leads.service.PredefinedFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@com.leads.audit.Audited(entity = "lead-settings") // 01 Part B item 9: writes are audited
@RequestMapping("/settings")
@RequiredArgsConstructor
@Tag(name = "Lead Settings & Dynamic Engine", description = "Endpoints for managing dynamic fields, custom statuses, purposes, and company feature toggles. "
        + "Reads need company scope. Writes (POST/PUT/DELETE) also need the LEAD_SETTINGS action; with flag lead_settings.company_wide_writes ON for the organization "
        + "they need company-wide scope too (COMPANY level or above), otherwise 403 FORBIDDEN (module 04, F-12).")
public class LeadSettingsController {

    private final LeadSettingsService settingsService;
    private final DynamicFieldService dynamicFieldService;
    private final PredefinedFieldService predefinedFieldService;

    // --- Dynamic Lead Statuses ---
    @GetMapping("/statuses")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Statuses by Company", description = "Retrieves all custom statuses with hex colors and stage types.")
    public ResponseEntity<List<LeadStatusDto>> getStatuses(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getStatusesByCompany(companyId));
    }

    @PostMapping("/statuses")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    @Operation(summary = "Create Custom Status", description = "Adds a custom status with hex color code and sequence.")
    public ResponseEntity<LeadStatusDto> createStatus(@RequestParam Long companyId, @Valid @RequestBody LeadStatusDto dto) {
        return ResponseEntity.ok(settingsService.createStatus(companyId, dto));
    }

    @PutMapping("/statuses/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Custom Status", description = "Updates a status color code, name, or stage type.")
    public ResponseEntity<LeadStatusDto> updateStatus(@PathVariable Long id, @Valid @RequestBody LeadStatusDto dto) {
        return ResponseEntity.ok(settingsService.updateStatus(id, dto));
    }

    @DeleteMapping("/statuses/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    @Operation(summary = "Delete Custom Status")
    public ResponseEntity<Void> deleteStatus(@PathVariable Long id) {
        settingsService.deleteStatus(id);
        return ResponseEntity.ok().build();
    }

    // --- Dynamic Lead Purposes ---
    @GetMapping("/purposes")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Lead Purposes by Company")
    public ResponseEntity<List<LeadPurposeDto>> getPurposes(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getPurposesByCompany(companyId));
    }

    @PostMapping("/purposes")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    @Operation(summary = "Create Lead Purpose")
    public ResponseEntity<LeadPurposeDto> createPurpose(@RequestParam Long companyId, @Valid @RequestBody LeadPurposeDto dto) {
        return ResponseEntity.ok(settingsService.createPurpose(companyId, dto));
    }

    @PutMapping("/purposes/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Lead Purpose")
    public ResponseEntity<LeadPurposeDto> updatePurpose(@PathVariable Long id, @Valid @RequestBody LeadPurposeDto dto) {
        return ResponseEntity.ok(settingsService.updatePurpose(id, dto));
    }

    @DeleteMapping("/purposes/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    @Operation(summary = "Delete Lead Purpose")
    public ResponseEntity<Void> deletePurpose(@PathVariable Long id) {
        settingsService.deletePurpose(id);
        return ResponseEntity.ok().build();
    }

    // --- Dynamic Lead Lost Reasons ---
    @GetMapping("/lost-reasons")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Lead Lost Reasons by Company")
    public ResponseEntity<List<LeadLostReasonDto>> getLostReasons(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getLostReasonsByCompany(companyId));
    }

    @PostMapping("/lost-reasons")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    @Operation(summary = "Create Lead Lost Reason")
    public ResponseEntity<LeadLostReasonDto> createLostReason(@RequestParam Long companyId, @Valid @RequestBody LeadLostReasonDto dto) {
        return ResponseEntity.ok(settingsService.createLostReason(companyId, dto));
    }

    @PutMapping("/lost-reasons/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Lead Lost Reason")
    public ResponseEntity<LeadLostReasonDto> updateLostReason(@PathVariable Long id, @Valid @RequestBody LeadLostReasonDto dto) {
        return ResponseEntity.ok(settingsService.updateLostReason(id, dto));
    }

    @DeleteMapping("/lost-reasons/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    @Operation(summary = "Delete Lead Lost Reason")
    public ResponseEntity<Void> deleteLostReason(@PathVariable Long id) {
        settingsService.deleteLostReason(id);
        return ResponseEntity.ok().build();
    }

    // --- Dynamic Lead Sources ---
    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Lead Sources by Company", description = "Retrieves all lead sources — built-in and custom — with icons and colors.")
    public ResponseEntity<List<LeadSourceDto>> getSources(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getSourcesByCompany(companyId));
    }

    @PostMapping("/sources")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    @Operation(summary = "Create Lead Source", description = "Adds a custom lead source with an icon and color.")
    public ResponseEntity<LeadSourceDto> createSource(@RequestParam Long companyId, @Valid @RequestBody LeadSourceDto dto) {
        return ResponseEntity.ok(settingsService.createSource(companyId, dto));
    }

    @PutMapping("/sources/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Lead Source", description = "Updates a custom source's name, code, icon, or color — built-in sources can't be edited.")
    public ResponseEntity<LeadSourceDto> updateSource(@PathVariable Long id, @Valid @RequestBody LeadSourceDto dto) {
        return ResponseEntity.ok(settingsService.updateSource(id, dto));
    }

    @DeleteMapping("/sources/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    @Operation(summary = "Delete Lead Source", description = "Deletes a custom source — built-in sources can't be deleted.")
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        settingsService.deleteSource(id);
        return ResponseEntity.ok().build();
    }

    // --- Dynamic Custom Form Fields ---
    @GetMapping("/dynamic-fields")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Dynamic Custom Fields", description = "Retrieves dynamic fields for dynamic form rendering in lead create/edit.")
    public ResponseEntity<List<DynamicFieldDto>> getDynamicFields(
            @RequestParam Long companyId,
            @RequestParam(defaultValue = "LEAD") String module
    ) {
        return ResponseEntity.ok(dynamicFieldService.getFieldsByCompanyAndModule(companyId, module));
    }

    @PostMapping("/dynamic-fields")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    @Operation(summary = "Create Dynamic Custom Field", description = "Creates text, select, multiselect, date, time, switch, radio, checkbox, etc.")
    public ResponseEntity<DynamicFieldDto> createDynamicField(@RequestParam Long companyId, @Valid @RequestBody DynamicFieldDto dto) {
        return ResponseEntity.ok(dynamicFieldService.createField(companyId, dto));
    }

    @PutMapping("/dynamic-fields/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Dynamic Custom Field")
    public ResponseEntity<DynamicFieldDto> updateDynamicField(@PathVariable Long id, @Valid @RequestBody DynamicFieldDto dto) {
        return ResponseEntity.ok(dynamicFieldService.updateField(id, dto));
    }

    @DeleteMapping("/dynamic-fields/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    @Operation(summary = "Delete Dynamic Custom Field")
    public ResponseEntity<Void> deleteDynamicField(@PathVariable Long id) {
        dynamicFieldService.deleteField(id);
        return ResponseEntity.ok().build();
    }

    // --- Predefined Lead Fields (native fields: hide/mandatory config only — no add/delete) ---
    @GetMapping("/predefined-fields")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Predefined Lead Field Config", description = "Lists all 12 native Lead form fields with their visibility/mandatory config; 4 are permanently locked.")
    public ResponseEntity<List<PredefinedFieldConfigDto>> getPredefinedFields(@RequestParam Long companyId) {
        return ResponseEntity.ok(predefinedFieldService.getConfigsByCompany(companyId));
    }

    @PutMapping("/predefined-fields/{fieldKey}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Predefined Lead Field Config", description = "Toggles visibility/mandatory for a non-locked predefined field — locked fields (Name, Mobile, Purpose, Source) reject this.")
    public ResponseEntity<PredefinedFieldConfigDto> updatePredefinedField(
            @RequestParam Long companyId, @PathVariable String fieldKey, @RequestBody PredefinedFieldConfigDto dto) {
        return ResponseEntity.ok(predefinedFieldService.updateConfig(companyId, fieldKey, dto));
    }

    // --- Company General Settings (Product linkage toggle, auto-assign) ---
    @GetMapping("/company-lead-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Company Lead Settings", description = "Retrieves company-wide settings like product linking and auto assignment.")
    public ResponseEntity<CompanyLeadSettingsDto> getCompanyLeadSettings(@RequestParam Long companyId) {
        return ResponseEntity.ok(settingsService.getCompanyLeadSettings(companyId));
    }

    @PutMapping("/company-lead-settings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    @Operation(summary = "Update Company Lead Settings", description = "Toggles link product with lead option, auto assignment, and WhatsApp auto-reply.")
    public ResponseEntity<CompanyLeadSettingsDto> updateCompanyLeadSettings(@RequestParam Long companyId, @RequestBody CompanyLeadSettingsDto dto) {
        return ResponseEntity.ok(settingsService.updateCompanyLeadSettings(companyId, dto));
    }
}
