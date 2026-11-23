package com.leads.dto.delivery;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDeliverySettingsDto {
    private Long id;
    private Long companyId;
    private Boolean deliveryModuleEnabled;
    private Boolean requireApprovalForPostpone;
    private Boolean requireApprovalForClose;
    private Integer maxDeliveryAttempts;
}
