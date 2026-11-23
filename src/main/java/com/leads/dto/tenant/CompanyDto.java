package com.leads.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDto {
    private Long id;

    // Not @NotNull: required on create (checked explicitly in the service, since
    // this DTO is also used for update, where the org never changes and the
    // frontend's edit form doesn't send it).
    private Long organizationId;
    private String organizationName;

    @NotBlank(message = "Company name is required")
    private String name;

    @NotBlank(message = "Company code is required")
    private String code;

    private String taxNumber;
    private String currency;
    private String logoUrl;
    private String timezone;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
