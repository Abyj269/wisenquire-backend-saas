package com.leads.model.enums;

// Normalized across providers: Meta's effective_status values (ACTIVE,
// PAUSED, ARCHIVED, DELETED, PENDING_REVIEW, ...) and Google's ad_group_ad
// .status (ENABLED, PAUSED, REMOVED) both collapse onto this set so the
// report and the periodic-sync status filter never need provider-specific
// branching.
public enum AdStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED,
    DELETED,
    UNKNOWN;

    /** Meta's ad.effective_status — includes several paused/review sub-states this app doesn't distinguish. */
    public static AdStatus fromMeta(String effectiveStatus) {
        if (effectiveStatus == null) return UNKNOWN;
        return switch (effectiveStatus.toUpperCase()) {
            case "ACTIVE" -> ACTIVE;
            case "PAUSED", "CAMPAIGN_PAUSED", "ADSET_PAUSED" -> PAUSED;
            case "ARCHIVED" -> ARCHIVED;
            case "DELETED" -> DELETED;
            default -> UNKNOWN;
        };
    }

    /** Google's ad_group_ad.status — ENABLED/PAUSED/REMOVED, a coarser set than Meta's. */
    public static AdStatus fromGoogle(String status) {
        if (status == null) return UNKNOWN;
        return switch (status.toUpperCase()) {
            case "ENABLED" -> ACTIVE;
            case "PAUSED" -> PAUSED;
            case "REMOVED" -> DELETED;
            default -> UNKNOWN;
        };
    }
}
