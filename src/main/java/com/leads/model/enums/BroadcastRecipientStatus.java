package com.leads.model.enums;

public enum BroadcastRecipientStatus {
    PENDING,
    /** Module 16 P1: claimed by the chunked sender; its Meta call has not started (the recovery may send it). */
    SENDING_ITEM,
    /** Step 5.22 (owner decision A, at most once): its Meta call has started; the result is not saved yet. */
    ATTEMPTING,
    /**
     * Step 5.22: the sender stopped during this recipient's Meta call, so whether Meta accepted it is unknown.
     * The recovery does NOT resend it (at most once: no duplicate message); it is shown as not confirmed.
     */
    UNCONFIRMED,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
