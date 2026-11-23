package com.leads.service;

import com.leads.dto.tenant.*;
import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantManagementService {
    private final TenantAccess access;
    private final DelegationPolicy delegation;

    private final OrganizationRepository orgRepo;
    private final CompanyRepository companyRepo;
    private final BranchRepository branchRepo;
    private final RoleRepository roleRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final LeadSettingsService leadSettingsService;
    private final BranchTrustedIpRepository branchTrustedIpRepo;

    // --- Organizations ---
    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizations() {
        access.superAdmin();
        return orgRepo.findAll().stream().map(this::mapOrgToDto).collect(Collectors.toList());
    }

    @Transactional
    public OrganizationDto createOrganization(OrganizationDto dto) {
        access.superAdmin();
        Organization org = Organization.builder()
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .subscriptionPlan(dto.getSubscriptionPlan() != null ? dto.getSubscriptionPlan() : "ENTERPRISE")
                .logoUrl(dto.getLogoUrl())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();
        return mapOrgToDto(orgRepo.save(org));
    }

    @Transactional
    public OrganizationDto updateOrganization(Long id, OrganizationDto dto) {
        access.organizationAdmin(id);
        Organization org = orgRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + id));

        org.setName(dto.getName());
        org.setCode(dto.getCode().toUpperCase());
        if (dto.getSubscriptionPlan() != null) {
            org.setSubscriptionPlan(dto.getSubscriptionPlan());
        }
        org.setLogoUrl(dto.getLogoUrl());
        if (dto.getIsActive() != null) {
            org.setIsActive(dto.getIsActive());
        }
        return mapOrgToDto(orgRepo.save(org));
    }

    @Transactional(readOnly = true)
    public OrganizationSummaryDto getOrganizationSummary(Long orgId) {
        access.organizationAdmin(orgId);
        if (!orgRepo.existsById(orgId)) {
            throw new IllegalArgumentException("Organization not found: " + orgId);
        }
        List<Company> companies = companyRepo.findByOrganizationId(orgId);
        List<Long> companyIds = companies.stream().map(Company::getId).collect(Collectors.toList());
        long branchCount = companyIds.isEmpty() ? 0 : branchRepo.findByCompanyIdIn(companyIds).size();
        long userCount = userRepo.findByOrganizationId(orgId).size();

        return OrganizationSummaryDto.builder()
                .organizationId(orgId)
                .companyCount(companies.size())
                .branchCount(branchCount)
                .userCount(userCount)
                .build();
    }

    // --- Companies ---
    @Transactional(readOnly = true)
    public List<CompanyDto> getCompaniesByOrg(Long orgId) {
        access.organization(orgId);
        return companyRepo.findByOrganizationId(orgId).stream().filter(c -> access.canCompany(access.actor(), c)).map(this::mapCompanyToDto).collect(Collectors.toList());
    }

    @Transactional
    public CompanyDto createCompany(CompanyDto dto) {
        access.organizationAdmin(dto.getOrganizationId());
        if (dto.getOrganizationId() == null) {
            throw new IllegalArgumentException("Organization ID is required");
        }
        Organization org = orgRepo.findById(dto.getOrganizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + dto.getOrganizationId()));

        Company company = Company.builder()
                .organization(org)
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .taxNumber(dto.getTaxNumber())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "USD")
                .logoUrl(dto.getLogoUrl())
                .timezone(dto.getTimezone() != null ? dto.getTimezone() : "Asia/Dubai")
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();
        Company saved = companyRepo.save(company);
        leadSettingsService.seedDefaultPipeline(saved);
        leadSettingsService.seedDefaultSources(saved);
        leadSettingsService.seedDefaultLostReasons(saved);
        return mapCompanyToDto(saved);
    }

    @Transactional
    public CompanyDto updateCompany(Long id, CompanyDto dto) {
        access.companyWide(id);
        Company company = companyRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + id));

        company.setName(dto.getName());
        company.setCode(dto.getCode().toUpperCase());
        company.setTaxNumber(dto.getTaxNumber());
        if (dto.getCurrency() != null) {
            company.setCurrency(dto.getCurrency());
        }
        company.setLogoUrl(dto.getLogoUrl());
        if (dto.getTimezone() != null) {
            company.setTimezone(dto.getTimezone());
        }
        if (dto.getIsActive() != null) {
            company.setIsActive(dto.getIsActive());
        }
        return mapCompanyToDto(companyRepo.save(company));
    }

    // --- Branches ---
    @Transactional(readOnly = true)
    public List<BranchDto> getBranchesByCompany(Long companyId) {
        access.company(companyId);
        return branchRepo.findByCompanyId(companyId).stream().filter(access::canBranch).map(this::mapBranchToDto).collect(Collectors.toList());
    }

    @Transactional
    public BranchDto createBranch(BranchDto dto) {
        access.companyWide(dto.getCompanyId());
        Company company = companyRepo.findById(dto.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + dto.getCompanyId()));

        Branch branch = Branch.builder()
                .company(company)
                .name(dto.getName())
                .code(dto.getCode().toUpperCase())
                .city(dto.getCity())
                .phone(dto.getPhone())
                .email(dto.getEmail())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .ipRestrictionEnabled(Boolean.TRUE.equals(dto.getIpRestrictionEnabled()))
                .defaultCurrency(dto.getDefaultCurrency())
                .defaultVatPercent(dto.getDefaultVatPercent())
                .logoUrl(dto.getLogoUrl())
                .address(dto.getAddress())
                .trnNumber(dto.getTrnNumber())
                .quotationTerms(dto.getQuotationTerms())
                .build();
        return mapBranchToDto(branchRepo.save(branch));
    }

    @Transactional
    public BranchDto updateBranch(Long id, BranchDto dto) {
        access.branch(id);
        Branch branch = branchRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + id));

        branch.setName(dto.getName());
        branch.setCode(dto.getCode().toUpperCase());
        branch.setCity(dto.getCity());
        branch.setPhone(dto.getPhone());
        branch.setEmail(dto.getEmail());
        if (dto.getIsActive() != null) {
            branch.setIsActive(dto.getIsActive());
        }
        if (dto.getIpRestrictionEnabled() != null) {
            branch.setIpRestrictionEnabled(dto.getIpRestrictionEnabled());
        }
        branch.setDefaultCurrency(dto.getDefaultCurrency());
        branch.setDefaultVatPercent(dto.getDefaultVatPercent());
        branch.setLogoUrl(dto.getLogoUrl());
        branch.setAddress(dto.getAddress());
        branch.setTrnNumber(dto.getTrnNumber());
        branch.setQuotationTerms(dto.getQuotationTerms());
        return mapBranchToDto(branchRepo.save(branch));
    }

    /**
     * "Delete Branch" is a soft delete (isActive=false/true) - branches are
     * referenced by leads, customers, users, WhatsApp configs and more, so a
     * hard DELETE would either cascade-destroy that history or fail on FK
     * constraints. An inactive branch drops out of active lists and can no
     * longer receive new leads/logins, but its data is preserved and this
     * is reversible via the same method with active=true.
     * <p>
     * Deliberately does NOT go through access.branch(id) - that helper
     * rejects any already-inactive branch outright (by design, for normal
     * operational access), which would make reactivating one impossible.
     * access.company() + canBranch() give the same authorization check
     * without that one-way-door trap.
     */
    @Transactional
    public BranchDto setBranchActive(Long id, boolean active) {
        Branch branch = branchRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + id));
        access.company(branch.getCompany().getId());
        if (!access.canBranch(branch)) {
            throw new org.springframework.security.access.AccessDeniedException("This record is outside your permitted scope.");
        }
        branch.setIsActive(active);
        return mapBranchToDto(branchRepo.save(branch));
    }

    // --- Branch trusted IPs ---
    @Transactional(readOnly = true)
    public List<BranchTrustedIpDto> getTrustedIpsForBranch(Long branchId) {
        access.branch(branchId);
        return branchTrustedIpRepo.findByBranchIdOrderByIdAsc(branchId).stream()
                .map(this::mapTrustedIpToDto).collect(Collectors.toList());
    }

    @Transactional
    public BranchTrustedIpDto addTrustedIp(BranchTrustedIpUpsertRequest req) {
        access.branch(req.getBranchId());
        Branch branch = branchRepo.findById(req.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));

        BranchTrustedIp entry = BranchTrustedIp.builder()
                .branch(branch)
                .ipAddress(req.getIpAddress().trim())
                .label(req.getLabel())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        return mapTrustedIpToDto(branchTrustedIpRepo.save(entry));
    }

    @Transactional
    public void deleteTrustedIp(Long id) {
        access.record(com.leads.model.entity.BranchTrustedIp.class, id);
        if (!branchTrustedIpRepo.existsById(id)) {
            throw new IllegalArgumentException("Trusted IP entry not found: " + id);
        }
        branchTrustedIpRepo.deleteById(id);
    }

    public BranchTrustedIpDto mapTrustedIpToDto(BranchTrustedIp e) {
        return BranchTrustedIpDto.builder()
                .id(e.getId())
                .branchId(e.getBranch().getId())
                .branchName(e.getBranch().getName())
                .ipAddress(e.getIpAddress())
                .label(e.getLabel())
                .isActive(e.getIsActive())
                .createdAt(e.getCreatedAt())
                .build();
    }

    // --- Roles & Permissions ---
    @Transactional(readOnly = true)
    public List<RoleDto> getRolesByOrg(Long orgId) {
        access.organization(orgId);
        return roleRepo.findByOrganizationId(orgId).stream().map(this::mapRoleToDto).collect(Collectors.toList());
    }

    @Transactional
    public RoleDto createRole(RoleDto dto) {
        delegation.role(dto.getOrganizationId(), dto.getRoleLevel(), dto.getPermissions(), null);
        Organization org = dto.getOrganizationId() != null ? orgRepo.findById(dto.getOrganizationId()).orElse(null) : null;

        Role role = Role.builder()
                .organization(org)
                .roleName(dto.getRoleName())
                .roleLevel(dto.getRoleLevel())
                .description(dto.getDescription())
                .isSystemRole(false)
                .build();

        if (dto.getPermissions() != null) {
            List<RolePermission> permissions = new ArrayList<>();
            for (RolePermissionDto pDto : dto.getPermissions()) {
                RolePermission p = RolePermission.builder()
                        .role(role)
                        .moduleName(pDto.getModuleName())
                        .canView(Boolean.TRUE.equals(pDto.getCanView()))
                        .canAdd(Boolean.TRUE.equals(pDto.getCanAdd()))
                        .canEdit(Boolean.TRUE.equals(pDto.getCanEdit()))
                        .canDelete(Boolean.TRUE.equals(pDto.getCanDelete()))
                        .canExport(Boolean.TRUE.equals(pDto.getCanExport()))
                        .canApprove(Boolean.TRUE.equals(pDto.getCanApprove()))
                        .canAssign(Boolean.TRUE.equals(pDto.getCanAssign()))
                        .canPrint(Boolean.TRUE.equals(pDto.getCanPrint()))
                        .build();
                permissions.add(p);
            }
            role.setPermissions(permissions);
        }

        return mapRoleToDto(roleRepo.save(role));
    }

    @Transactional
    public RoleDto updateRole(Long id, RoleDto dto) {
        Role role = roleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));

        delegation.role(role.getOrganization() != null ? role.getOrganization().getId() : null, dto.getRoleLevel() != null ? dto.getRoleLevel() : role.getRoleLevel(), dto.getPermissions(), role);
        role.setRoleName(dto.getRoleName());
        role.setDescription(dto.getDescription());
        // A seeded system role's level is load-bearing for permission checks
        // elsewhere (ROLE_SUPER_ADMIN, etc.) — everything else about it stays
        // editable, but its level doesn't.
        if (!Boolean.TRUE.equals(role.getIsSystemRole()) && dto.getRoleLevel() != null) {
            role.setRoleLevel(dto.getRoleLevel());
        }

        if (dto.getPermissions() != null) {
            role.getPermissions().clear();
            for (RolePermissionDto pDto : dto.getPermissions()) {
                role.getPermissions().add(RolePermission.builder()
                        .role(role)
                        .moduleName(pDto.getModuleName())
                        .canView(Boolean.TRUE.equals(pDto.getCanView()))
                        .canAdd(Boolean.TRUE.equals(pDto.getCanAdd()))
                        .canEdit(Boolean.TRUE.equals(pDto.getCanEdit()))
                        .canDelete(Boolean.TRUE.equals(pDto.getCanDelete()))
                        .canExport(Boolean.TRUE.equals(pDto.getCanExport()))
                        .canApprove(Boolean.TRUE.equals(pDto.getCanApprove()))
                        .canAssign(Boolean.TRUE.equals(pDto.getCanAssign()))
                        .canPrint(Boolean.TRUE.equals(pDto.getCanPrint()))
                        .build());
            }
        }

        roleRepo.saveAndFlush(role);
        userRepo.revokeSessionsForRole(id);
        return mapRoleToDto(role);
    }

    // --- Users ---
    @Transactional(readOnly = true)
    public List<UserDto> getUsersByOrg(Long orgId) {
        access.organizationAdmin(orgId);
        return userRepo.findByOrganizationId(orgId).stream().map(this::mapUserToDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserDto> getUsersByCompany(Long companyId) {
        access.company(companyId);
        // A BRANCH-level caller (e.g. a Branch Manager role granted
        // USER_MANAGEMENT:VIEW) must only see their own branch's team, not
        // every user across the whole company - this previously returned
        // every user with access to the company regardless of branch.
        List<Long> permittedBranches = access.branchIds();
        return userRepo.findByAllowedCompanyId(companyId).stream()
                .filter(u -> permittedBranches == null
                        || u.getAllowedBranches().stream().anyMatch(b -> permittedBranches.contains(b.getId())))
                .map(this::mapUserToDto).collect(Collectors.toList());
    }

    @Transactional
    public UserDto createUser(UserCreateRequest req) {
        Organization org = req.getOrganizationId() != null ? orgRepo.findById(req.getOrganizationId()).orElse(null) : null;
        Role role = roleRepo.findById(req.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + req.getRoleId()));

        Set<Company> companies = new LinkedHashSet<>();
        if (req.getAllowedCompanyIds() != null) {
            companies.addAll(companyRepo.findAllById(req.getAllowedCompanyIds()));
        }

        Set<Branch> branches = new LinkedHashSet<>();
        if (req.getAllowedBranchIds() != null) {
            branches.addAll(branchRepo.findAllById(req.getAllowedBranchIds()));
        }

        validateScopeIds(req.getAllowedCompanyIds(), req.getAllowedBranchIds(), companies, branches);
        delegation.user(req.getOrganizationId(), role, req.getIsSuperAdmin(), companies, branches, null);
        User user = User.builder()
                .organization(org)
                .role(role)
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .phone(req.getPhone())
                .isSuperAdmin(Boolean.TRUE.equals(req.getIsSuperAdmin()) || role.getRoleLevel() == RoleLevel.SUPER_ADMIN)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .isTrustedUser(Boolean.TRUE.equals(req.getIsTrustedUser()))
                .allowedCompanies(companies)
                .allowedBranches(branches)
                .build();

        return mapUserToDto(userRepo.save(user));
    }

    @Transactional
    public UserDto updateUser(Long id, UserUpdateRequest req) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        Role role = roleRepo.findById(req.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + req.getRoleId()));

        Set<Company> companies = new LinkedHashSet<>();
        if (req.getAllowedCompanyIds() != null) {
            companies.addAll(companyRepo.findAllById(req.getAllowedCompanyIds()));
        }

        Set<Branch> branches = new LinkedHashSet<>();
        if (req.getAllowedBranchIds() != null) {
            branches.addAll(branchRepo.findAllById(req.getAllowedBranchIds()));
        }

        validateScopeIds(req.getAllowedCompanyIds(), req.getAllowedBranchIds(), companies, branches);
        delegation.user(user.getOrganization() != null ? user.getOrganization().getId() : null, role, req.getIsSuperAdmin(), companies, branches, user);
        if (access.isSuper(user) && (!Boolean.TRUE.equals(req.getIsSuperAdmin()) && role.getRoleLevel()!=RoleLevel.SUPER_ADMIN || Boolean.FALSE.equals(req.getIsActive()))) {
            throw new IllegalArgumentException("Platform administrators must remain active; use a separate secured administrator lifecycle procedure.");
        }
        user.setRole(role);
        user.setFirstName(req.getFirstName());
        user.setLastName(req.getLastName());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setIsSuperAdmin(Boolean.TRUE.equals(req.getIsSuperAdmin()) || role.getRoleLevel() == RoleLevel.SUPER_ADMIN);
        if (req.getIsActive() != null) {
            user.setIsActive(req.getIsActive());
        }
        if (req.getIsTrustedUser() != null) {
            user.setIsTrustedUser(req.getIsTrustedUser());
        }
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        user.setSessionVersion(user.getSessionVersion()+1);
        user.setAllowedCompanies(companies);
        user.setAllowedBranches(branches);

        return mapUserToDto(userRepo.save(user));
    }

    private void validateScopeIds(List<Long> companyIds, List<Long> branchIds, Set<Company> companies, Set<Branch> branches) {
        if ((companyIds != null && new java.util.HashSet<>(companyIds).size()!=companies.size()) || (branchIds != null && new java.util.HashSet<>(branchIds).size()!=branches.size())) throw new IllegalArgumentException("Unknown company or branch in user scope.");
    }

    public OrganizationDto mapOrgToDto(Organization o) {
        return OrganizationDto.builder()
                .id(o.getId())
                .name(o.getName())
                .code(o.getCode())
                .subscriptionPlan(o.getSubscriptionPlan())
                .logoUrl(o.getLogoUrl())
                .isActive(o.getIsActive())
                .createdAt(o.getCreatedAt())
                .build();
    }

    public CompanyDto mapCompanyToDto(Company c) {
        return CompanyDto.builder()
                .id(c.getId())
                .organizationId(c.getOrganization().getId())
                .organizationName(c.getOrganization().getName())
                .name(c.getName())
                .code(c.getCode())
                .taxNumber(c.getTaxNumber())
                .currency(c.getCurrency())
                .logoUrl(c.getLogoUrl())
                .timezone(c.getTimezone())
                .isActive(c.getIsActive())
                .createdAt(c.getCreatedAt())
                .build();
    }

    public BranchDto mapBranchToDto(Branch b) {
        return BranchDto.builder()
                .id(b.getId())
                .companyId(b.getCompany().getId())
                .companyName(b.getCompany().getName())
                .name(b.getName())
                .code(b.getCode())
                .city(b.getCity())
                .phone(b.getPhone())
                .email(b.getEmail())
                .isActive(b.getIsActive())
                .ipRestrictionEnabled(b.getIpRestrictionEnabled())
                .defaultCurrency(b.getDefaultCurrency())
                .defaultVatPercent(b.getDefaultVatPercent())
                .logoUrl(b.getLogoUrl())
                .address(b.getAddress())
                .trnNumber(b.getTrnNumber())
                .quotationTerms(b.getQuotationTerms())
                .createdAt(b.getCreatedAt())
                .build();
    }

    public RoleDto mapRoleToDto(Role r) {
        List<RolePermissionDto> pList = r.getPermissions().stream()
                .map(p -> RolePermissionDto.builder()
                        .id(p.getId())
                        .moduleName(p.getModuleName())
                        .canView(p.getCanView())
                        .canAdd(p.getCanAdd())
                        .canEdit(p.getCanEdit())
                        .canDelete(p.getCanDelete())
                        .canExport(p.getCanExport())
                        .canApprove(p.getCanApprove())
                        .canAssign(p.getCanAssign())
                        .canPrint(p.getCanPrint())
                        .build())
                .collect(Collectors.toList());

        return RoleDto.builder()
                .id(r.getId())
                .organizationId(r.getOrganization() != null ? r.getOrganization().getId() : null)
                .organizationName(r.getOrganization() != null ? r.getOrganization().getName() : "Global")
                .roleName(r.getRoleName())
                .roleLevel(r.getRoleLevel())
                .description(r.getDescription())
                .isSystemRole(r.getIsSystemRole())
                .permissions(pList)
                .build();
    }

    public UserDto mapUserToDto(User u) {
        return UserDto.builder()
                .id(u.getId())
                .organizationId(u.getOrganization() != null ? u.getOrganization().getId() : null)
                .organizationName(u.getOrganization() != null ? u.getOrganization().getName() : "Global")
                .roleId(u.getRole().getId())
                .roleName(u.getRole().getRoleName())
                .roleLevel(u.getRole().getRoleLevel())
                .email(u.getEmail())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .fullName(u.getFullName())
                .phone(u.getPhone())
                .isSuperAdmin(u.getIsSuperAdmin())
                .isActive(u.getIsActive())
                .isTrustedUser(u.getIsTrustedUser())
                .allowedCompanyIds(u.getAllowedCompanies().stream().map(Company::getId).collect(Collectors.toList()))
                .allowedCompanyNames(u.getAllowedCompanies().stream().map(Company::getName).collect(Collectors.toList()))
                .allowedBranchIds(u.getAllowedBranches().stream().map(Branch::getId).collect(Collectors.toList()))
                .allowedBranchNames(u.getAllowedBranches().stream().map(Branch::getName).collect(Collectors.toList()))
                .createdAt(u.getCreatedAt())
                .build();
    }
}
