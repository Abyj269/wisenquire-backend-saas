package com.leads.jobs;

import io.micrometer.core.instrument.Metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts job bodies that actually ran (i.e. got the ShedLock lock). Called first thing inside each
 * locked job. Exposed as Micrometer counter {@code jobs.runs{job=...}} (Prometheus:
 * {@code jobs_runs_total}); the in-JVM count is what the two-instance test reads.
 */
public final class JobRuns {

    private static final Map<ScheduledJob, AtomicLong> COUNTS = new EnumMap<>(ScheduledJob.class);

    static {
        for (ScheduledJob job : ScheduledJob.values()) COUNTS.put(job, new AtomicLong());
    }

    private JobRuns() { }

    public static void record(ScheduledJob job) {
        COUNTS.get(job).incrementAndGet();
        Metrics.counter("jobs.runs", "job", job.lockName).increment();
    }

    /** Runs since this JVM started (all application contexts in it). */
    public static long count(ScheduledJob job) { return COUNTS.get(job).get(); }
}
