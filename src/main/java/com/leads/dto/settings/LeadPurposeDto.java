package com.leads.dto.settings;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadPurposeDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Purpose name is required")
    private String purposeName;
    private com.leads.model.enums.PurposeClassification classification;

    private String description;
    private Integer sortOrder;
    private Boolean isActive;
}
