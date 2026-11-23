package com.leads.dto.lead;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadConvertRequest {
    @NotNull(message = "Lead ID is required")
    private Long leadId;

    private String customerAccountName;
    private BigDecimal dealValue;
    private String conversionNotes;
}
