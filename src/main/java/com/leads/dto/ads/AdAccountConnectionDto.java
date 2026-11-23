package com.leads.dto.ads;

import com.leads.model.enums.AdProvider;
import com.leads.model.enums.AdSyncStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdAccountConnectionDto {
    private Long id;
    private Long companyId;
    private Long branchId; // null = company-wide default
    private String branchName;
    private AdProvider provider;
    private String externalAccountId;
    private String displayName;
    private Boolean isActive;

    // Meta — masked, real token never sent to the browser.
    private String accessTokenMasked;
    private String appId;

    // Google — client id/login-customer-id aren't secrets, shown in full; the rest masked.
    private String googleDeveloperTokenMasked;
    private String googleClientId;
    private String googleClientSecretMasked;
    private String googleRefreshTokenMasked;
    private String googleLoginCustomerId;

    private LocalDateTime lastSyncAt;
    private AdSyncStatus lastSyncStatus;
    private String lastSyncError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
