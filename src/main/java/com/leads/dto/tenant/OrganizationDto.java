package com.leads.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationDto {
    private Long id;

    @NotBlank(message = "Organization name is required")
    private String name;

    @NotBlank(message = "Organization code is required")
    private String code;

    private Boolean isActive;
    private String subscriptionPlan;
    private String logoUrl;
    private LocalDateTime createdAt;
}
