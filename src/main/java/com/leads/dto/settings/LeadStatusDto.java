package com.leads.dto.settings;

import com.leads.model.enums.StageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadStatusDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Status name is required")
    private String statusName;

    @NotBlank(message = "Status code is required")
    private String statusCode;

    @NotBlank(message = "Color code hex is required")
    private String colorCode; // e.g. "#10B981"

    @NotNull(message = "Stage type is required")
    private StageType stageType;

    private Integer sortOrder;
    private Boolean isDefault;
    private Boolean isActive;

    /** True for the company's seeded pipeline stages — those can be renamed
     *  or recolored but never deleted; only statuses a user added later can be. */
    private Boolean isSystemDefined;
}
