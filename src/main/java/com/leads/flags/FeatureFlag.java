package com.leads.flags;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * D13: every feature flag, code-owned (CLAUDE.md rule 5: never free text). The rows in
 * {@code feature_flags} (V52) must match this enum exactly; {@code FeatureFlagsIT} checks it.
 * <p>
 * Flags are operational and platform-controlled; they are not plan entitlements (D7).
 * {@code exposed} = returned by {@code GET /me/feature-flags} for the frontend.
 * Every flag has an owner and an expiry: remove it once it has been at 100% for 2 weeks.
 * {@code FeatureFlagHygieneTest} warns in CI about expired flags.
 */
public enum FeatureFlag {
    ERRORS_UNIFORM_NOT_FOUND("errors.uniform_not_found", false, "module-01", "2027-06-30"),
    /** Step 5.3 (FE): global D12 error UI (403 toast, retry countdown, tenant-state pages, upgrade modal). */
    ERRORS_UI_HANDLER("errors.ui_handler", true, "module-01", "2027-06-30"),
    /** Module 03 P1 (D15): companies limited to the 7 year-1 timezones (backend + frontend list). */
    TENANT_TIMEZONE_ALLOWLIST("tenant.timezone_allowlist", true, "module-03", "2027-12-31"),
    OWN_SCOPE_ENFORCED("own_scope.enforced", true, "module-01", "2027-12-31"),
    OWN_SCOPE_INCLUDE_UNASSIGNED("own_scope.include_unassigned", true, "module-01", "2027-12-31"),
    AUTH_SUBJECT_USER_ID("auth.subject_user_id", false, "module-02", "2027-06-30"),
    AUTH_REFRESH_TOKENS("auth.refresh_tokens", true, "module-02", "2027-06-30"),
    BROADCAST_CHUNKED_TX("broadcast.chunked_tx", false, "module-16", "2027-06-30"),
    BROADCAST_QUEUE_V2("broadcast.queue_v2", false, "module-16", "2027-12-31"),
    WEBHOOK_ASYNC_INBOX("webhook.async_inbox", false, "module-12", "2027-12-31"),
    REPORTS_SQL_V2("reports.sql_v2", false, "module-10", "2027-12-31"),
    SIGNUP_PUBLIC("signup.public", true, "module-20", "2028-06-30"),
    WA_COST_DISPLAY("wa.cost_display", true, "module-23", "2028-06-30"),
    WA_TENANT_SEND_LIMIT("wa.tenant_send_limit", true, "module-23", "2028-06-30"),
    BILLING_ENFORCE("billing.enforce", true, "module-22", "2028-06-30"),
    BILLING_EINVOICE("billing.einvoice", true, "module-22", "2028-06-30"),
    BRANDING_RUNTIME("branding.runtime", true, "module-24", "2028-06-30");

    public final String key;
    public final boolean exposed;
    public final String owner;
    public final LocalDate expiresAt;

    FeatureFlag(String key, boolean exposed, String owner, String expiresAt) {
        this.key = key;
        this.exposed = exposed;
        this.owner = owner;
        this.expiresAt = LocalDate.parse(expiresAt);
    }

    public static Optional<FeatureFlag> byKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
