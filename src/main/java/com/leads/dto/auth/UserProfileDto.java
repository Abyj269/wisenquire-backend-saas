package com.leads.dto.auth;

import com.leads.model.enums.RoleLevel;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileDto {
    private boolean gettingStartedSeen;
    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private Boolean isSuperAdmin;
    private Boolean isTrustedUser;
    private Long organizationId;
    private String organizationName;
    private Long roleId;
    private String roleName;
    private RoleLevel roleLevel;
    private List<CompanyScopeDto> allowedCompanies;
    private List<BranchScopeDto> allowedBranches;
    private Map<String, PermissionSetDto> permissions; // moduleName -> {canView, canAdd, canEdit, canDelete, canExport, canApprove, canAssign, canPrint}

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CompanyScopeDto {
        private Long id;
        private String name;
        private String code;
        private String currency;
        private String logoUrl;
        private String timezone;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BranchScopeDto {
        private Long id;
        private Long companyId;
        private String name;
        private String code;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PermissionSetDto {
        private Boolean canView;
        private Boolean canAdd;
        private Boolean canEdit;
        private Boolean canDelete;
        private Boolean canExport;
        private Boolean canApprove;
        private Boolean canAssign;
        private Boolean canPrint;
    }
}
