package com.leads.it.matrix;

/**
 * What a matrix row expects. The HTTP status for a denial is defined once, here.
 * <p>
 * Today a {@code TenantAccess} denial is {@code AccessDeniedException} -> 403. D12
 * (01 Part B item 3) makes out-of-scope and missing records both 404 {@code NOT_FOUND};
 * when D12 lands, change {@link #DENIED} to 404 and every X-* row follows.
 */
public enum Outcome {
    /** The persona may do this: any 2xx. */
    ALLOWED(-1),
    /** Out of scope. */
    DENIED(403),
    /** No or invalid token. */
    UNAUTHENTICATED(401);

    public final int status;

    Outcome(int status) { this.status = status; }

    public boolean matches(int actual) {
        return this == ALLOWED ? actual >= 200 && actual < 300 : actual == status;
    }

    public String describe() { return this == ALLOWED ? "2xx" : String.valueOf(status); }
}
