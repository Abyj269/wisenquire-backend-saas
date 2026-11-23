package com.leads.dto.lead;

import com.leads.dto.customer.CustomerSummaryDto;
import com.leads.dto.settings.LeadPurposeDto;
import com.leads.dto.settings.LeadSourceDto;
import com.leads.dto.settings.LeadStatusDto;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadDto {
    private Long id;
    private Long organizationId;
    private String organizationName;
    private Long companyId;
    private String companyName;
    private Long branchId;
    private String branchName;
    private CustomerSummaryDto customer;
    private String leadNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private String whatsappNumber;
    private String customerCompanyName;
    private String jobTitle;
    private String nationality;
    private String preferredLanguage;
    private LeadSourceDto source;
    private LeadPurposeDto purpose;
    private LeadStatusDto status;
    private Long assignedToUserId;
    private String assignedToName;
    private BigDecimal estimatedDealValue;
    private LocalDate closingDate;
    private String notes;
    private Map<String, Object> dynamicValues; // parsed dynamic field values
    private Boolean isConverted;
    private LocalDateTime convertedAt;
    private Long lostReasonId;
    private String lostReasonText;
    private String lostNotes;
    private List<LeadProductDto> products;

    /** This lead's branch's quotation defaults, already resolved with the company-currency fallback — ready to prefill a new Quotation with, never enforced. */
    private String defaultCurrency;
    private BigDecimal defaultVatPercent;
    private String defaultQuotationTerms;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
