package com.leads.service;

import com.leads.dto.tenant.RolePermissionDto;
import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DelegationPolicy {
    private final TenantAccess access;
    public void role(Long orgId, RoleLevel level, List<RolePermissionDto> permissions, Role existing) {
        User actor = access.actor();
        if (access.isSuper(actor)) return;
        access.organization(orgId);
        if (level == null || level.ordinal() < actor.getRole().getRoleLevel().ordinal()
            || level == RoleLevel.SUPER_ADMIN || (existing != null && (Boolean.TRUE.equals(existing.getIsSystemRole())
            || existing.getId().equals(actor.getRole().getId())))) throw denied();
        if (permissions != null) for (RolePermissionDto p : permissions) {
            String module = p.getModuleName().toUpperCase(Locale.ROOT);
            boolean[] flags={Boolean.TRUE.equals(p.getCanView()),Boolean.TRUE.equals(p.getCanAdd()),Boolean.TRUE.equals(p.getCanEdit()),Boolean.TRUE.equals(p.getCanDelete()),Boolean.TRUE.equals(p.getCanExport()),Boolean.TRUE.equals(p.getCanApprove()),Boolean.TRUE.equals(p.getCanAssign()),Boolean.TRUE.equals(p.getCanPrint())};
            String[] actions={"VIEW","ADD","EDIT","DELETE","EXPORT","APPROVE","ASSIGN","PRINT"};
            for(int i=0;i<actions.length;i++) if(flags[i]) access.permission(module+":"+actions[i]);
        }
    }
    public void user(Long orgId, Role role, Boolean superAdmin, Collection<Company> companies, Collection<Branch> branches, User existing) {
        User actor=access.actor();
        if (!access.isSuper(actor)) {
            access.organization(orgId);
            if(Boolean.TRUE.equals(superAdmin) || role.getRoleLevel()==RoleLevel.SUPER_ADMIN
                || role.getRoleLevel().ordinal()<actor.getRole().getRoleLevel().ordinal()
                || (existing!=null && (access.isSuper(existing) || existing.getId().equals(actor.getId())
                || existing.getOrganization()==null || !Objects.equals(orgId, existing.getOrganization().getId())
                || existing.getRole().getRoleLevel().ordinal()<actor.getRole().getRoleLevel().ordinal()))) throw denied();
            for(RolePermission p:role.getPermissions()) {
                role(orgId,role.getRoleLevel(),List.of(RolePermissionDto.builder().moduleName(p.getModuleName())
                    .canView(p.getCanView()).canAdd(p.getCanAdd()).canEdit(p.getCanEdit()).canDelete(p.getCanDelete())
                    .canExport(p.getCanExport()).canApprove(p.getCanApprove()).canAssign(p.getCanAssign()).canPrint(p.getCanPrint()).build()),null);
            }
        }
        if(role.getRoleLevel()!=RoleLevel.SUPER_ADMIN && (orgId==null || role.getOrganization()==null || !orgId.equals(role.getOrganization().getId()))) throw new IllegalArgumentException("Role and user must belong to the same organization.");
        for(Company company:companies) {
            if(!Objects.equals(orgId,company.getOrganization().getId())) throw new IllegalArgumentException("Company is outside the user's organization.");
            access.company(company.getId());
        }
        for(Branch branch:branches) {
            access.branch(branch.getId());
            if(companies.stream().noneMatch(c->c.getId().equals(branch.getCompany().getId()))) throw new IllegalArgumentException("Every branch must belong to a selected company.");
        }
        if(role.getRoleLevel()==RoleLevel.COMPANY && companies.isEmpty()) throw new IllegalArgumentException("Select at least one company.");
        if(role.getRoleLevel()==RoleLevel.BRANCH && branches.isEmpty()) throw new IllegalArgumentException("Select at least one branch.");
    }
    private AccessDeniedException denied(){return new AccessDeniedException("You cannot delegate or modify this level of access.");}
}
