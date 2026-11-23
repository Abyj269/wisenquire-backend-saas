package com.leads.dto.ads;

import com.leads.model.enums.AdProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdAccountConnectionUpsertRequest {
    /** Null when connecting a new account; set to update an existing connection by its own id. */
    private Long id;

    @NotNull(message = "Company is required")
    private Long companyId;

    /** Null = company-wide default connection for this provider. Non-null = this one branch's override. */
    private Long branchId;

    @NotNull(message = "Provider is required")
    private AdProvider provider;

    @NotBlank(message = "Ad account ID is required")
    private String externalAccountId;

    private String displayName;
    private Boolean isActive;

    // Meta — required for a new META connection. Left blank on update to keep the previously saved token unchanged.
    private String accessToken;
    private String appId;

    // Google — all four required for a new GOOGLE connection. Left blank on update to keep the previously saved value unchanged.
    private String googleDeveloperToken;
    private String googleClientId;
    private String googleClientSecret;
    private String googleRefreshToken;
    private String googleLoginCustomerId;
}
