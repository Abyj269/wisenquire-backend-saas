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
     * D14 (01 Part B, Step 5.3): returned so the role editor can show it. READ_ONLY: ignored on
     * create/update until module 03 makes it editable (with DelegationPolicy checking the request).
     */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private com.leads.model.enums.DataScope dataScope;
    private List<RolePermissionDto> permissions;
}
