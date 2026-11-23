package com.leads.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchTrustedIpUpsertRequest {
    @NotNull(message = "Branch ID is required")
    private Long branchId;

    @NotBlank(message = "IP address is required")
    private String ipAddress;

    private String label;
    private Boolean isActive;
}
