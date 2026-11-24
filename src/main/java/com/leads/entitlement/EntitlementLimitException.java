package com.leads.entitlement;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;

import java.util.Map;

/** D12 ENTITLEMENT_LIMIT (409), with {@code details.metric/limit/current} for the upgrade modal. */
public class EntitlementLimitException extends ApiException {
    public EntitlementLimitException(Metric metric, long limit, long current) {
        super(ErrorCode.ENTITLEMENT_LIMIT, "Your plan allows " + limit + " " + metric.key.replace('_', ' ') + ".",
                Map.of("metric", metric.key, "limit", limit, "current", current, "upgradeUrl", "/billing/plans"));
    }
}
