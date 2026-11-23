package com.leads.dto.reports;

import lombok.*;

import java.time.LocalDateTime;

/** One row in the Customers report's acquired/regained/lost lists. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSummaryDto {
    private Long id;
    private String name;
    private String mobileNumber;
    private String homeBranchName;
    /** Meaning depends on the list this row is in: firstLeadAt for "acquired", the comeback lead's createdAt for "regained", lastLeadAt for "lost". */
    private LocalDateTime referenceDate;
    /** Only set for "regained" (the dormancy gap that was broken) and "lost" (days since last activity). */
    private Integer gapDays;
}
