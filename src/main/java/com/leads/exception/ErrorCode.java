package com.leads.exception;

import org.springframework.http.HttpStatus;

/**
 * D12: the closed set of error codes in every error body ({@code {code, message, details, traceId}}).
 * The frontend maps by code, never by message. Adding a code is an API change (OpenAPI).
 * <p>
 * {@link #PAYLOAD_TOO_LARGE} and {@link #INTERNAL_ERROR} are not in the plan's D12 table; they cover
 * the existing 413 and the catch-all 500 so that every body has a code.
 */
public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    SESSION_REVOKED(HttpStatus.UNAUTHORIZED),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    CONFLICT(HttpStatus.CONFLICT),
    ENTITLEMENT_LIMIT(HttpStatus.CONFLICT),
    FEATURE_NOT_IN_PLAN(HttpStatus.FORBIDDEN),
    TENANT_TRIAL_EXPIRED(HttpStatus.PAYMENT_REQUIRED),
    TENANT_PAST_DUE(HttpStatus.PAYMENT_REQUIRED),
    TENANT_SUSPENDED(HttpStatus.LOCKED),
    WA_SEND_LIMIT(HttpStatus.TOO_MANY_REQUESTS),
    TRIAL_SEND_CAP(HttpStatus.TOO_MANY_REQUESTS),
    AI_USAGE_LIMIT(HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    FEATURE_DISABLED(HttpStatus.NOT_FOUND),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    public final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }
}
