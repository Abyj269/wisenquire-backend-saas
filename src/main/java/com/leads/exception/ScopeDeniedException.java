package com.leads.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Raised by {@code TenantAccess} when a record, company or branch is outside the caller's scope.
 * Extends AccessDeniedException so existing callers and tests keep working.
 * Status: 403 FORBIDDEN today; 404 NOT_FOUND when flag {@code errors.uniform_not_found} is on
 * for the caller's org, so a caller cannot tell "exists elsewhere" from "does not exist" (F-9).
 * A permission denial ({@code @PreAuthorize}, {@code access.permission}) stays 403 either way.
 */
public class ScopeDeniedException extends AccessDeniedException {
    public ScopeDeniedException(String message) { super(message); }
}
