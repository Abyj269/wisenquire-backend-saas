package com.leads.controller;

import com.leads.dto.ai.*;
import com.leads.service.AiBranchSettingsService;
import com.leads.service.AiProviderConfigService;
import com.leads.service.AiUsageGuardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@com.leads.audit.Audited(entity = "ai-settings") // 01 Part B item 9: writes are audited
@RequestMapping("/ai/settings")
@RequiredArgsConstructor
@Tag(name = "AI Settings", description = "Branch-wise AI provider connections and context/usage guardrails")
public class AiSettingsController {

    private final AiProviderConfigService providerConfigService;
    private final AiBranchSettingsService branchSettingsService;
    private final AiUsageGuardService usageGuard;

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    @Operation(summary = "List AI provider connections across a company's branches")
    public ResponseEntity<List<AiProviderConfigDto>> getProvidersByCompany(@RequestParam Long companyId) {
        return ResponseEntity.ok(providerConfigService.getConfigsByCompany(companyId));
    }

    @GetMapping("/providers/branch/{branchId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    @Operation(summary = "List a single branch's AI provider connections")
    public ResponseEntity<List<AiProviderConfigDto>> getProvidersByBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(providerConfigService.getConfigsByBranch(branchId));
    }

    @PostMapping("/providers")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:EDIT')")
    @Operation(summary = "Create or update a branch's AI provider connection")
    public ResponseEntity<AiProviderConfigDto> saveProvider(@Valid @RequestBody AiProviderConfigUpsertRequest req) {
        return ResponseEntity.ok(providerConfigService.saveConfig(req));
    }

    @PostMapping("/providers/{id}/select")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:EDIT')")
    @Operation(summary = "Mark this connection as the branch's active AI provider")
    public ResponseEntity<AiProviderConfigDto> selectProvider(@PathVariable Long id) {
        return ResponseEntity.ok(providerConfigService.selectProvider(id));
    }

    @DeleteMapping("/providers/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:DELETE')")
    @Operation(summary = "Remove a branch's AI provider connection")
    public ResponseEntity<Void> deleteProvider(@PathVariable Long id) {
        providerConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/{id}/test")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    @Operation(summary = "Test a branch's AI provider connection with a minimal completion call")
    public ResponseEntity<AiTestConnectionResult> testProviderConnection(@PathVariable Long id) {
        return ResponseEntity.ok(providerConfigService.testConnection(id));
    }

    @GetMapping("/branches/{branchId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    @Operation(summary = "Get a branch's AI context/usage guardrail settings (conservative defaults if never configured)")
    public ResponseEntity<AiBranchSettingsDto> getBranchSettings(@PathVariable Long branchId) {
        return ResponseEntity.ok(branchSettingsService.get(branchId));
    }

    @PutMapping("/branches/{branchId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:EDIT')")
    @Operation(summary = "Update a branch's AI context/usage guardrail settings")
    public ResponseEntity<AiBranchSettingsDto> updateBranchSettings(@PathVariable Long branchId, @RequestBody AiBranchSettingsUpsertRequest req) {
        return ResponseEntity.ok(branchSettingsService.upsert(branchId, req));
    }

    @GetMapping("/branches/{branchId}/usage")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_SETTINGS:VIEW')")
    @Operation(summary = "Current day/month AI usage vs. this branch's configured caps")
    public ResponseEntity<AiUsageSnapshotDto> getUsage(@PathVariable Long branchId) {
        return ResponseEntity.ok(usageGuard.getUsageSnapshot(branchId));
    }
}
