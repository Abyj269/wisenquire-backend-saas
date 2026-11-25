package com.leads.config;

import com.leads.service.TenantAccess;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;

/**
 * 01 Part B item 6 (F-3): hands the submitting thread's context to an executor thread:
 * {@link TenantContext} (org/user for logs), the whole MDC (traceId), the SecurityContext (who
 * started the work) and TenantAccess's system scope. Everything is restored/cleared after the task,
 * so a pooled thread never keeps another tenant's context.
 * <p>
 * Deliberately NOT copied: the ActorMemo (the user entity is re-read on the worker thread).
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable task) {
        Optional<TenantContext.Data> tenant = TenantContext.get();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        SecurityContext security = SecurityContextHolder.getContext();
        Runnable scoped = TenantAccess.propagateSystemScope(task);
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            SecurityContext previousSecurity = SecurityContextHolder.getContext();
            try {
                if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
                tenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
                SecurityContext copy = SecurityContextHolder.createEmptyContext();
                copy.setAuthentication(security.getAuthentication());
                SecurityContextHolder.setContext(copy);
                scoped.run();
            } finally {
                TenantContext.clear();
                SecurityContextHolder.setContext(previousSecurity);
                if (previousMdc != null) MDC.setContextMap(previousMdc); else MDC.clear();
            }
        };
    }
}
