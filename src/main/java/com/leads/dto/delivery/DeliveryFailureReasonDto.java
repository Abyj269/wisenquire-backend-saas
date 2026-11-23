package com.leads.dto.delivery;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryFailureReasonDto {
    private Long id;
    private Long companyId;

    @NotBlank(message = "Reason text is required")
    private String reasonText;

    private Integer sortOrder;
    private Boolean isActive;
}
