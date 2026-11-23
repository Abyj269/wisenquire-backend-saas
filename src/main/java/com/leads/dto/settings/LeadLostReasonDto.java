package com.leads.dto.settings;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadLostReasonDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Reason text is required")
    private String reasonText;

    private Integer sortOrder;
    private Boolean isActive;
}
