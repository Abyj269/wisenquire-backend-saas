package com.leads.model.enums;

public enum BroadcastRecipientStatus {
    PENDING,
    /** Module 16 P1: claimed by the chunked sender; not yet sent (no wamid) or sent and being recorded. */
    SENDING_ITEM,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
