package com.leads.dto.settings;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadSourceDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Source name is required")
    private String sourceName;

    @NotBlank(message = "Source code is required")
    private String sourceCode;

    private String icon;
    private String colorCode;
    private Integer sortOrder;
    private Boolean isActive;

    /** True for the company's seeded lead sources — never renamable, recolorable, or deletable; only a source added later can be. */
    private Boolean isSystemDefined;
}
