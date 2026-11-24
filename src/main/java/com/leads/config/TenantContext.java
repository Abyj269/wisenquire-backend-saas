package com.leads.config;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * Who the current request (or STOMP frame) is acting for, taken from the already-validated JWT
 * (plan D4). It is used for logging (MDC keys {@code orgId}, {@code userId}) and, from Phase 1,
 * for tenant-aware infrastructure.
 *
 * <p><b>Not an authorisation source.</b> Access decisions still go through {@code TenantAccess}.
 * The values come from the JWT, never from request headers or bodies.
 *
 * <p>The value is per thread. It is set by {@link JwtAuthenticationFilter} and
 * {@link StompSecurityInterceptor}, and cleared by {@link RequestTraceFilter} and the STOMP
 * interceptor. Propagation to {@code @Async} executors comes in Phase 1 (TaskDecorator).
 */
public final class TenantContext {

    public static final String MDC_ORG_ID = "orgId";
    public static final String MDC_USER_ID = "userId";

    /** organizationId is null for the org-less platform super-admin. */
    public record Data(Long userId, Long organizationId, String roleLevel) {}

    private static final ThreadLocal<Data> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Data data) {
        if (data == null) { clear(); return; }
        CURRENT.set(data);
        putOrRemove(MDC_USER_ID, data.userId());
        putOrRemove(MDC_ORG_ID, data.organizationId());
    }

    public static Optional<Data> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_ORG_ID);
    }

    private static void putOrRemove(String key, Object value) {
        if (value == null) MDC.remove(key); else MDC.put(key, String.valueOf(value));
    }
}
