package com.leads.jobs;

/** Every background job that takes a ShedLock lock. Metric tag {@code job} = {@link #lockName}. */
public enum ScheduledJob {
    ADS_SYNC(JobLocks.ADS_SYNC),
    BROADCAST_SCHEDULER(JobLocks.BROADCAST_SCHEDULER),
    WHATSAPP_RECEIPT_RECONCILE(JobLocks.WHATSAPP_RECEIPT_RECONCILE),
    WHATSAPP_RECEIPT_CLEANUP(JobLocks.WHATSAPP_RECEIPT_CLEANUP),
    WHATSAPP_CREDENTIAL_MIGRATION(JobLocks.WHATSAPP_CREDENTIAL_MIGRATION);

    public final String lockName;

    ScheduledJob(String lockName) { this.lockName = lockName; }
}
