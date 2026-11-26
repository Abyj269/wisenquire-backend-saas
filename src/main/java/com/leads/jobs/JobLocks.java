package com.leads.jobs;

/**
 * ShedLock lock names (01 Part B item 7). Constants because annotations need compile-time strings;
 * {@link ScheduledJob} is the code-owned enum that uses them.
 */
public final class JobLocks {
    public static final String ADS_SYNC = "ads-sync";
    public static final String BROADCAST_SCHEDULER = "broadcast-scheduler";
    public static final String BROADCAST_RECOVERY = "broadcast-recovery";
    public static final String WHATSAPP_RECEIPT_RECONCILE = "whatsapp-receipt-reconcile";
    public static final String WHATSAPP_RECEIPT_CLEANUP = "whatsapp-receipt-cleanup";
    public static final String WHATSAPP_CREDENTIAL_MIGRATION = "whatsapp-credential-migration";

    private JobLocks() { }
}
