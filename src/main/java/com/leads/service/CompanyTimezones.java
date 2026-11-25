package com.leads.service;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * D15 option A, extended (owner decision): year-1 companies use one of 7 fixed-offset zones, so
 * CompanyClock's offset arithmetic stays exact. Enforced only for organizations with flag
 * tenant.timezone_allowlist ON (module 03 P1; default OFF = any zone, as before).
 */
@Component
@RequiredArgsConstructor
public class CompanyTimezones {

    /** In display order (the frontend shows the same list). */
    public static final List<String> ALLOWED = List.of(
            "Asia/Kolkata", "Asia/Dubai", "Asia/Muscat", "Asia/Riyadh", "Asia/Qatar", "Asia/Bahrain", "Asia/Kuwait");

    private final FeatureFlags flags;

    public static boolean allowed(String zone) { return zone != null && ALLOWED.contains(zone); }

    /** Throws 400 VALIDATION_FAILED when the org's allow-list is on and {@code zone} is not one of the 7. Null = not changing. */
    public void check(Long organizationId, String zone) {
        if (zone == null || organizationId == null) return;
        if (!flags.isOn(FeatureFlag.TENANT_TIMEZONE_ALLOWLIST, organizationId)) return;
        if (!allowed(zone)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose one of the supported timezones.",
                    Map.of("fields", List.of(Map.of("field", "timezone", "message", "Must be one of " + String.join(", ", ALLOWED))), "allowed", ALLOWED));
        }
    }
}
