package com.leads.entitlement;

/**
 * D7 catalogue of plan features (yes/no). Code-owned: never reuse the free-text
 * {@code RolePermission.moduleName}. Plan data arrives in module 22; until then every
 * feature is allowed ({@link UnlimitedEntitlements}).
 */
public enum Feature {
    DELIVERIES,
    QUOTATIONS,
    ADS,
    AI,
    AUTOMATION,
    BROADCAST
}
