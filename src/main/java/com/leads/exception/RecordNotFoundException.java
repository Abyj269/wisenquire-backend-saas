package com.leads.exception;

/**
 * Raised by {@code TenantAccess.find/record} when the record does not exist.
 * Extends IllegalArgumentException so existing callers and tests keep working.
 * Status: 400 VALIDATION_FAILED today; 404 NOT_FOUND when flag {@code errors.uniform_not_found}
 * is on for the caller's org (D12 / F-9), the same as an out-of-scope record.
 */
public class RecordNotFoundException extends IllegalArgumentException {
    public RecordNotFoundException(String message) { super(message); }
}
