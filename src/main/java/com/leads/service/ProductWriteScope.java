package com.leads.service;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.model.entity.Company;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Module 05 P1 (F-12): the scope for every product-catalogue write. The catalogue is shared by the
 * whole company, so with flag {@code products.company_wide_writes} ON for the org a branch-level user
 * is refused (403 FORBIDDEN) even with PRODUCT_CATALOG rights. Flag OFF: company scope, as before.
 * Same rule as {@link LeadSettingsWriteScope}, with its own flag so products can be switched separately.
 */
@Component
@RequiredArgsConstructor
public class ProductWriteScope {

    private final TenantAccess access;
    private final FeatureFlags flags;

    public Company check(Long companyId) {
        Company company = access.company(companyId);
        if (access.branchIds() != null
                && flags.isOn(FeatureFlag.PRODUCTS_COMPANY_WIDE_WRITES, company.getOrganization().getId())) {
            throw new AccessDeniedException("Only company-level admins can change the product catalogue.");
        }
        return company;
    }
}
