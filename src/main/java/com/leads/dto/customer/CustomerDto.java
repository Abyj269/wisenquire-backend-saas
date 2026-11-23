package com.leads.dto.customer;

import com.leads.model.enums.CustomerStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDto {
    private Long id;
    private Long organizationId;
    private Long companyId;
    private String companyName;
    private Long homeBranchId;
    private String homeBranchName;

    /** All distinct branches this customer has a lead at — populated only on the single-record detail fetch. */
    private List<CustomerBranchDto> activeBranches;

    /** First-touch attribution — populated only on the single-record detail fetch, like activeBranches. */
    private String initialSourceName;

    /** Sum of estimatedDealValue across this customer's converted leads — populated only on the single-record detail fetch. */
    private java.math.BigDecimal totalRevenue;

    /** Count of this customer's leads currently in a Lost stage — populated only on the single-record detail fetch. */
    private Integer lostLeadsCount;

    private String name;
    private String mobileNumber;
    private String whatsappNumber;
    private String email;
    private String customerCompanyName;
    private String city;
    private CustomerStatus status;
    private Integer totalLeadsCount;
    private Integer convertedLeadsCount;
    private LocalDateTime firstLeadAt;
    private LocalDateTime lastLeadAt;
    private LocalDateTime lastConvertedAt;
    private String notes;
    private Boolean isBlocked;
    private LocalDateTime blockedAt;
    private String blockedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
