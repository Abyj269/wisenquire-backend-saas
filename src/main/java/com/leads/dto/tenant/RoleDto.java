package com.leads.dto.tenant;

import com.leads.model.enums.RoleLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleDto {
    private Long id;
    private Long organizationId;
    private String organizationName;

    @NotBlank(message = "Role name is required")
    private String roleName;

    @NotNull(message = "Role level is required")
    private RoleLevel roleLevel;

    private String description;
    private Boolean isSystemRole;
    /**
     * D14: LEVEL or OWN. Writable since module 03 P1: null on create = LEVEL, null on update = unchanged.
     * DelegationPolicy refuses a LEVEL role from an OWN creator.
     */
    private com.leads.model.enums.DataScope dataScope;
    private List<RolePermissionDto> permissions;
}
