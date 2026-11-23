package com.leads.controller;

import com.leads.dto.whatsapp.WhatsAppConfigDto;
import com.leads.dto.whatsapp.WhatsAppConfigUpsertRequest;
import com.leads.dto.whatsapp.WhatsAppTestConnectionResult;
import com.leads.service.WhatsAppConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/whatsapp/configs")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Branch Configuration", description = "Branch-wise WhatsApp Cloud API connection setup")
public class WhatsAppConfigController {

    private final WhatsAppConfigService configService;

    @GetMapping
    // Beyond the WhatsApp Setup admin screen (COMPANY_BRANCH_SETTINGS:VIEW),
    // this is also the prerequisite "which numbers exist" lookup for the
    // Templates page's number picker and the Inbox's number-filter dropdown
    // - a role granted only WHATSAPP_TEMPLATES:VIEW or WHATSAPP_LIVE_CHAT:VIEW
    // (not COMPANY_BRANCH_SETTINGS:VIEW, which is an admin-level permission
    // they may never be given) still needs to pass this call to use either
    // page at all. Was previously admin-only, which 403'd both pages for a
    // template/chat-only role - visibly in Templates (blocks the whole
    // page), silently in the Inbox (the picker just never appears, easy to
    // miss when there's only one number anyway).
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW') or hasAuthority('WHATSAPP_TEMPLATES:VIEW') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW')")
    @Operation(summary = "List branch-wise WhatsApp connections for a company")
    public ResponseEntity<List<WhatsAppConfigDto>> getByCompany(@RequestParam Long companyId) {
        return ResponseEntity.ok(configService.getConfigsByCompany(companyId));
    }

    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "List the WhatsApp numbers connected to a single branch — a branch may have more than one")
    public ResponseEntity<List<WhatsAppConfigDto>> getByBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(configService.getConfigsByBranch(branchId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Create or update a branch's WhatsApp Cloud API connection")
    public ResponseEntity<WhatsAppConfigDto> save(@Valid @RequestBody WhatsAppConfigUpsertRequest req) {
        return ResponseEntity.ok(configService.saveConfig(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:DELETE')")
    @Operation(summary = "Disconnect a branch's WhatsApp number")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        configService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Test a branch's WhatsApp connection against Meta's Graph API")
    public ResponseEntity<WhatsAppTestConnectionResult> testConnection(@PathVariable Long id) {
        return ResponseEntity.ok(configService.testConnection(id));
    }
}
