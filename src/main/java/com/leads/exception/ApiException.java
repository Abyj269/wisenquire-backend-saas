package com.leads.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D12: an error with a code-owned {@link ErrorCode}, a message written for end users, and
 * optional structured {@code details} (e.g. {@code metric/limit/current} for ENTITLEMENT_LIMIT).
 * The message is shown to users verbatim: never put internal state, SQL or ids of other tenants in it.
 */
public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) { this(code, message, Map.of()); }

    public ApiException(ErrorCode code, String message, Map<String, ?> details) {
        super(message);
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
