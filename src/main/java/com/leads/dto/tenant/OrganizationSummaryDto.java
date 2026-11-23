package com.leads.dto.tenant;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationSummaryDto {
    private Long organizationId;
    private long companyCount;
    private long branchCount;
    private long userCount;
}
