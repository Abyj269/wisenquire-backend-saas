package com.leads.dto.tenant;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchTrustedIpDto {
    private Long id;
    private Long branchId;
    private String branchName;
    private String ipAddress;
    private String label;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
