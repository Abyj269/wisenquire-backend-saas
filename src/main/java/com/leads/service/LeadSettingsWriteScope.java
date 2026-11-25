package com.leads.service;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.model.entity.Company;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Module 04 P1 (F-12): the scope for every lead-settings write (statuses, purposes, lost reasons,
 * sources, dynamic fields, predefined field configs, company lead settings). These are shared by the
 * whole company, so with flag {@code lead_settings.company_wide_writes} ON for the org a branch-level
 * user is refused (403 FORBIDDEN) even with LEAD_SETTINGS rights. Flag OFF: company scope, as before.
 * Reads stay on {@code TenantAccess.company()}: every role needs them for lead forms.
 */
@Component
@RequiredArgsConstructor
public class LeadSettingsWriteScope {

    private final TenantAccess access;
    private final FeatureFlags flags;

    public Company check(Long companyId) {
        Company company = access.company(companyId);
        if (access.branchIds() != null
                && flags.isOn(FeatureFlag.LEAD_SETTINGS_COMPANY_WIDE_WRITES, company.getOrganization().getId())) {
            throw new AccessDeniedException("Only company-level admins can change lead settings.");
        }
        return company;
    }
}
