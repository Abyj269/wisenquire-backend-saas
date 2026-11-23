package com.leads.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermissionDto {
    private Long id;

    @NotBlank(message = "Module name is required")
    private String moduleName;

    private Boolean canView;
    private Boolean canAdd;
    private Boolean canEdit;
    private Boolean canDelete;
    private Boolean canExport;
    private Boolean canApprove;
    private Boolean canAssign;
    private Boolean canPrint;
}
