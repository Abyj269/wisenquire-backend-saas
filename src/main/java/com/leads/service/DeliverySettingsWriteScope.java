package com.leads.service;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.model.entity.Company;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Module 09 P1 (F-12): the scope for delivery-settings writes (failure reasons, company delivery
 * settings). With flag {@code delivery_settings.company_wide_writes} ON for the org, branch-level
 * users are refused (403). Flag OFF: company scope, as before. Same rule as {@link LeadSettingsWriteScope}.
 */
@Component
@RequiredArgsConstructor
public class DeliverySettingsWriteScope {

    private final TenantAccess access;
    private final FeatureFlags flags;

    public Company check(Long companyId) {
        Company company = access.company(companyId);
        if (access.branchIds() != null
                && flags.isOn(FeatureFlag.DELIVERY_SETTINGS_COMPANY_WIDE_WRITES, company.getOrganization().getId())) {
            throw new AccessDeniedException("Only company-level admins can change delivery settings.");
        }
        return company;
    }
}
