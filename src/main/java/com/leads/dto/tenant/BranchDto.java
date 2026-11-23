package com.leads.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchDto {
    private Long id;

    @NotNull(message = "Company ID is required")
    private Long companyId;
    private String companyName;

    @NotBlank(message = "Branch name is required")
    private String name;

    @NotBlank(message = "Branch code is required")
    private String code;

    private String city;
    private String phone;
    private String email;
    private Boolean isActive;
    private Boolean ipRestrictionEnabled;
    private String defaultCurrency;
    private java.math.BigDecimal defaultVatPercent;
    private String logoUrl;
    private String address;
    private String trnNumber;
    private String quotationTerms;
    private LocalDateTime createdAt;
}
