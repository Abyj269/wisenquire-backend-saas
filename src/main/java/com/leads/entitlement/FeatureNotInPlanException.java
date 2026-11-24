package com.leads.entitlement;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;

import java.util.Map;

/** D12 FEATURE_NOT_IN_PLAN (403): the organization's plan does not include the feature. */
public class FeatureNotInPlanException extends ApiException {
    public FeatureNotInPlanException(Feature feature) {
        super(ErrorCode.FEATURE_NOT_IN_PLAN, "Your plan does not include this feature.",
                Map.of("feature", feature.name(), "upgradeUrl", "/billing/plans"));
    }
}
