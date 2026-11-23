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
public class UserCreateRequest {
    private Long organizationId;

    @NotNull(message = "Role ID is required")
    private Long roleId;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "First name is required")
    private String firstName;

    private String lastName;
    private String phone;
    private Boolean isSuperAdmin;
    private Boolean isActive;
    private Boolean isTrustedUser;

    private List<Long> allowedCompanyIds;
    private List<Long> allowedBranchIds;
}
