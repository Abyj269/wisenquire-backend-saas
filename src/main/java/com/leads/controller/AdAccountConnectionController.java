package com.leads.controller;

import com.leads.dto.ads.AdAccountConnectionDto;
import com.leads.dto.ads.AdAccountConnectionUpsertRequest;
import com.leads.dto.ads.AdConnectionTestResult;
import com.leads.service.AdAccountConnectionService;
import com.leads.service.AdSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@com.leads.audit.Audited(entity = "ads-connections") // 01 Part B item 9: writes are audited
@RequestMapping("/ads/connections")
@RequiredArgsConstructor
@Tag(name = "Ad Account Connections", description = "Company-default and branch-override Meta/Google Ads account connections")
public class AdAccountConnectionController {

    private final AdAccountConnectionService connectionService;
    private final AdSyncService syncService;
    private final com.leads.service.TenantAccess access;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "List a company's ad account connections (company-default + any branch overrides)")
    public ResponseEntity<List<AdAccountConnectionDto>> getByCompany(@RequestParam Long companyId) {
        return ResponseEntity.ok(connectionService.getConnectionsByCompany(companyId));
    }

    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "List the ad account connection(s) overriding a single branch")
    public ResponseEntity<List<AdAccountConnectionDto>> getByBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(connectionService.getConnectionsByBranch(branchId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Create or update a Meta/Google Ads account connection")
    public ResponseEntity<AdAccountConnectionDto> save(@Valid @RequestBody AdAccountConnectionUpsertRequest req) {
        return ResponseEntity.ok(connectionService.saveConnection(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:DELETE')")
    @Operation(summary = "Disconnect an ad account")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        connectionService.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Test a connection against the provider's API")
    public ResponseEntity<AdConnectionTestResult> testConnection(@PathVariable Long id) {
        return ResponseEntity.ok(connectionService.testConnection(id));
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Sync this connection's ads/spend right now (synchronous, single connection)")
    public ResponseEntity<Void> syncNow(@PathVariable Long id) {
        syncService.syncNow(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/backfill")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Backfill this connection's historical ads/spend (asynchronous — check lastSyncStatus for completion)")
    public ResponseEntity<Void> backfill(@PathVariable Long id) {
        access.record(com.leads.model.entity.AdAccountConnection.class, id);
        syncService.backfillAsync(id);
        return ResponseEntity.accepted().build();
    }
}
