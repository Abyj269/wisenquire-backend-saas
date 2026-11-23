package com.leads.dto.customer;

import com.leads.model.enums.CustomerStatus;
import lombok.*;

/** Lightweight customer projection embedded in LeadDto — just enough for the Lead Detail page's customer card. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSummaryDto {
    private Long id;
    private String name;
    private String mobileNumber;
    private String whatsappNumber;
    private CustomerStatus status;
    private Integer totalLeadsCount;
    private Integer convertedLeadsCount;

    /**
     * Only populated by {@link com.leads.service.CustomerService#suggest} (the
     * phone-number typeahead) — null everywhere else this DTO is built, since
     * computing it costs an extra query per row and most callers (e.g. the
     * customer summary embedded on every lead in a list) don't need it.
     */
    private Boolean hasActiveLead;
}
