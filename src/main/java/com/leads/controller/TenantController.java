package com.leads.controller;

import com.leads.dto.tenant.*;
import com.leads.service.TenantManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@com.leads.audit.Audited(entity = "tenant") // 01 Part B item 9: writes are audited
@RequestMapping("/tenant")
@RequiredArgsConstructor
@Tag(name = "Tenant & 4-Tier RBAC Management", description = "Endpoints for Organizations, Companies, Branches, Roles, and User Scoping")
public class TenantController {

    private final TenantManagementService tenantService;

    // --- Organizations ---
    @GetMapping("/organizations")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get All Organizations (Super Admin)")
    public ResponseEntity<List<OrganizationDto>> getOrganizations() {
        return ResponseEntity.ok(tenantService.getAllOrganizations());
    }

    @PostMapping("/organizations")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create Organization (Super Admin)")
    public ResponseEntity<OrganizationDto> createOrganization(@Valid @RequestBody OrganizationDto dto) {
        return ResponseEntity.ok(tenantService.createOrganization(dto));
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION')")
    @Operation(summary = "Update Organization Details")
    public ResponseEntity<OrganizationDto> updateOrganization(@PathVariable Long id, @Valid @RequestBody OrganizationDto dto) {
        return ResponseEntity.ok(tenantService.updateOrganization(id, dto));
    }

    @GetMapping("/organizations/{id}/summary")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Get Organization Summary (companies, branches, users counts)")
    public ResponseEntity<OrganizationSummaryDto> getOrganizationSummary(@PathVariable Long id) {
        return ResponseEntity.ok(tenantService.getOrganizationSummary(id));
    }

    // --- Companies ---
    @GetMapping("/companies")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "Get Companies by Organization")
    public ResponseEntity<List<CompanyDto>> getCompanies(@RequestParam Long organizationId) {
        return ResponseEntity.ok(tenantService.getCompaniesByOrg(organizationId));
    }

    @PostMapping("/companies")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('COMPANY_BRANCH_SETTINGS:ADD')")
    @Operation(summary = "Create Company")
    public ResponseEntity<CompanyDto> createCompany(@Valid @RequestBody CompanyDto dto) {
        return ResponseEntity.ok(tenantService.createCompany(dto));
    }

    @PutMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Update Company Details")
    public ResponseEntity<CompanyDto> updateCompany(@PathVariable Long id, @Valid @RequestBody CompanyDto dto) {
        return ResponseEntity.ok(tenantService.updateCompany(id, dto));
    }

    // --- Branches ---
    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Branches by Company")
    public ResponseEntity<List<BranchDto>> getBranches(@RequestParam Long companyId) {
        return ResponseEntity.ok(tenantService.getBranchesByCompany(companyId));
    }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:ADD')")
    @Operation(summary = "Create Branch")
    public ResponseEntity<BranchDto> createBranch(@Valid @RequestBody BranchDto dto) {
        return ResponseEntity.ok(tenantService.createBranch(dto));
    }

    @PutMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Update Branch Details")
    public ResponseEntity<BranchDto> updateBranch(@PathVariable Long id, @Valid @RequestBody BranchDto dto) {
        return ResponseEntity.ok(tenantService.updateBranch(id, dto));
    }

    @DeleteMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:DELETE')")
    @Operation(summary = "Delete Branch", description = "Soft-deletes a branch (marks it inactive) - see TenantManagementService.setBranchActive for why this isn't a hard delete.")
    public ResponseEntity<BranchDto> deleteBranch(@PathVariable Long id) {
        return ResponseEntity.ok(tenantService.setBranchActive(id, false));
    }

    @PutMapping("/branches/{id}/reactivate")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Reactivate a previously deleted Branch")
    public ResponseEntity<BranchDto> reactivateBranch(@PathVariable Long id) {
        return ResponseEntity.ok(tenantService.setBranchActive(id, true));
    }

    // --- Branch trusted IPs (login access control) ---
    @GetMapping("/branches/{branchId}/trusted-ips")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:VIEW')")
    @Operation(summary = "List Trusted Login IPs for a Branch")
    public ResponseEntity<List<BranchTrustedIpDto>> getTrustedIps(@PathVariable Long branchId) {
        return ResponseEntity.ok(tenantService.getTrustedIpsForBranch(branchId));
    }

    @PostMapping("/branches/trusted-ips")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Add a Trusted Login IP to a Branch")
    public ResponseEntity<BranchTrustedIpDto> addTrustedIp(@Valid @RequestBody BranchTrustedIpUpsertRequest req) {
        return ResponseEntity.ok(tenantService.addTrustedIp(req));
    }

    @DeleteMapping("/branches/trusted-ips/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('COMPANY_BRANCH_SETTINGS:EDIT')")
    @Operation(summary = "Remove a Trusted Login IP from a Branch")
    public ResponseEntity<Void> deleteTrustedIp(@PathVariable Long id) {
        tenantService.deleteTrustedIp(id);
        return ResponseEntity.noContent().build();
    }

    // --- Roles ---
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_PERMISSION_MANAGEMENT:VIEW')")
    @Operation(summary = "Get Roles by Organization")
    public ResponseEntity<List<RoleDto>> getRoles(@RequestParam Long organizationId) {
        return ResponseEntity.ok(tenantService.getRolesByOrg(organizationId));
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_PERMISSION_MANAGEMENT:ADD')")
    @Operation(summary = "Create Role with Minute Permissions")
    public ResponseEntity<RoleDto> createRole(@Valid @RequestBody RoleDto dto) {
        return ResponseEntity.ok(tenantService.createRole(dto));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_PERMISSION_MANAGEMENT:EDIT')")
    @Operation(summary = "Update Role Details & Permissions")
    public ResponseEntity<RoleDto> updateRole(@PathVariable Long id, @Valid @RequestBody RoleDto dto) {
        return ResponseEntity.ok(tenantService.updateRole(id, dto));
    }

    // --- Users ---
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('USER_MANAGEMENT:VIEW') or hasAuthority('LEADS:VIEW') or hasAuthority('WHATSAPP_AUTOMATIONS:ADD') or hasAuthority('WHATSAPP_AUTOMATIONS:EDIT')")
    @Operation(summary = "Get Users by Company or Org")
    public ResponseEntity<List<UserDto>> getUsers(@RequestParam(required = false) Long organizationId, @RequestParam(required = false) Long companyId) {
        if (companyId != null) {
            return ResponseEntity.ok(tenantService.getUsersByCompany(companyId));
        }
        return ResponseEntity.ok(tenantService.getUsersByOrg(organizationId));
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('USER_MANAGEMENT:ADD')")
    @Operation(summary = "Create User with Company & Branch Mappings")
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserCreateRequest req) {
        return ResponseEntity.ok(tenantService.createUser(req));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_ORGANIZATION') or hasAuthority('ROLE_COMPANY') or hasAuthority('USER_MANAGEMENT:EDIT')")
    @Operation(summary = "Update User Details, Role, & Scoping")
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        return ResponseEntity.ok(tenantService.updateUser(id, req));
    }
}
