package com.leads.dto.reports;

import lombok.*;

/** One bucket (week or month, depending on range length) in the Customers report's trend chart. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendPointDto {
    private String label;
    private long acquired;
    private long regained;
    private long lost;
}
