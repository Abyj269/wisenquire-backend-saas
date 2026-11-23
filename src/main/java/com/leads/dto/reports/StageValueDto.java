package com.leads.dto.reports;

import com.leads.model.enums.StageType;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageValueDto {
    private StageType stageType;
    private long leadCount;
    private BigDecimal totalValue;
}
