package com.leads.dto.tenant;

import com.leads.model.enums.RoleLevel;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private Long id;
    private Long organizationId;
    private String organizationName;
    private Long roleId;
    private String roleName;
    private RoleLevel roleLevel;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private Boolean isSuperAdmin;
    private Boolean isActive;
    private Boolean isTrustedUser;
    private List<Long> allowedCompanyIds;
    private List<String> allowedCompanyNames;
    private List<Long> allowedBranchIds;
    private List<String> allowedBranchNames;
    private LocalDateTime createdAt;
}
