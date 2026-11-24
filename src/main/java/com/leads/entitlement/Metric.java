package com.leads.entitlement;

/**
 * D7 catalogue of metered plan limits. Code-owned. {@code key} is the value used in
 * {@code details.metric} of an ENTITLEMENT_LIMIT error (D12) and, later, in plan data (module 22).
 */
public enum Metric {
    USERS("users"),
    COMPANIES("companies"),
    BRANCHES("branches"),
    WHATSAPP_NUMBERS("whatsapp_numbers"),
    AUTOMATIONS("automations"),
    BROADCASTS_PER_MONTH("broadcasts_per_month"),
    STORAGE_MB("storage_mb");

    public final String key;

    Metric(String key) { this.key = key; }
}
