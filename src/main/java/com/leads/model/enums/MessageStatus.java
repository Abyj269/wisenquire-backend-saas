package com.leads.model.enums;

public enum MessageStatus {
    /** S3-d (module 12): reserved before the Graph call; replaced by the result right after. */
    PENDING,
    ACCEPTED,
    UNKNOWN,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
