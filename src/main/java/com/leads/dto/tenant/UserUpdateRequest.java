package com.leads.dto.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpdateRequest {
    @NotNull(message = "Role ID is required")
    private Long roleId;

    @NotBlank(message = "First name is required")
    private String firstName;

    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    private String phone;

    /** Left blank to keep the current password unchanged. */
    private String password;

    private Boolean isSuperAdmin;
    private Boolean isActive;
    private Boolean isTrustedUser;

    private List<Long> allowedCompanyIds;
    private List<Long> allowedBranchIds;
}
