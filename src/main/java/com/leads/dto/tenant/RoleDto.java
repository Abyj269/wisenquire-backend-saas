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
    private List<RolePermissionDto> permissions;
}
