package com.leads.dto.settings;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyLeadSettingsDto {
    private Long id;
    private Long companyId;
    private Boolean linkProductEnabled;
    private Boolean autoAssignEnabled;
    private String autoAssignStrategy;
    private Long defaultStatusId;
    private String defaultStatusName;
    private Integer customerLostAfterDays;
    private Boolean whatsappAutoCreateLeadEnabled;
    private String automationConflictStrategy;
}
