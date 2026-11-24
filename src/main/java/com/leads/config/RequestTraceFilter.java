package com.leads.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Outermost filter. It gives every request a trace id (MDC key {@code traceId}, echoed back in the
 * {@code X-Request-Id} response header) and guarantees that the MDC and {@link TenantContext} are
 * cleared when the request ends. Without that, a pooled Tomcat thread could carry one tenant's ids
 * into the next request's logs.
 *
 * <p>An incoming {@code X-Request-Id} is reused only if it is a short, safe token. Otherwise a new
 * one is generated, so a client cannot inject text into log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_TRACE_ID = "traceId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(HEADER));
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TRACE_ID);
        }
    }

    static String resolveTraceId(String incoming) {
        return incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }
}
