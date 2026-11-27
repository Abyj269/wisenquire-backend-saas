package com.leads.model.enums;

public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    WAITING,
    CANCELLED,
    /** Module 17 P1: not run because the automation executor's queue was full. */
    SKIPPED_QUEUE_FULL
}
