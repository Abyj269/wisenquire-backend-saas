package com.leads.service;

import com.leads.config.AsyncConfig;
import com.leads.config.RequestTraceFilter;
import com.leads.config.SchedulingConfig;
import com.leads.config.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 01 §7 "TaskDecorator": MDC, tenant, login and system scope are visible inside the automation
 * (@Async) and broadcast executors, and nothing is left on the pooled thread afterwards.
 * Uses the real executor beans from SchedulingConfig / AsyncConfig.
 */
class TaskContextPropagationTest {

    @AfterEach
    void clean() { TenantContext.clear(); MDC.clear(); SecurityContextHolder.clearContext(); }

    record Seen(String trace, Long org, String user, Long systemCompany) { }

    private static Seen runOn(ThreadPoolTaskExecutor executor) throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        executor.submit(() -> seen.set(new Seen(MDC.get(RequestTraceFilter.MDC_TRACE_ID),
                TenantContext.get().map(TenantContext.Data::organizationId).orElse(null),
                SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getName(),
                TenantAccessTestHooks.systemCompany()))).get(10, TimeUnit.SECONDS);
        return seen.get();
    }

    private static void enterRequestContext() {
        MDC.put(RequestTraceFilter.MDC_TRACE_ID, "trace-123");
        TenantContext.set(new TenantContext.Data(7L, 42L, "BRANCH"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent@x.test", null, List.of()));
    }

    @Test
    void automationExecutorSeesTheSubmittersContext() throws Exception {
        ThreadPoolTaskExecutor automation = new SchedulingConfig().automationExecutor();
        try {
            enterRequestContext();
            assertEquals(new Seen("trace-123", 42L, "agent@x.test", null), runOn(automation));
        } finally { automation.shutdown(); }
    }

    @Test
    void broadcastExecutorSeesTheSubmittersContextAndSystemScope() throws Exception {
        ThreadPoolTaskExecutor broadcast = new AsyncConfig().broadcastExecutor();
        try {
            enterRequestContext();
            AtomicReference<Seen> seen = new AtomicReference<>();
            TenantAccess.inCompany(99L, () -> {
                try { seen.set(runOn(broadcast)); } catch (Exception e) { throw new IllegalStateException(e); }
            });
            assertEquals(new Seen("trace-123", 42L, "agent@x.test", 99L), seen.get());
        } finally { broadcast.shutdown(); }
    }

    @Test
    void nothingIsLeftOnThePooledThreadAfterwards() throws Exception {
        ThreadPoolTaskExecutor automation = new SchedulingConfig().automationExecutor();
        automation.setCorePoolSize(1);
        automation.setMaxPoolSize(1);
        try {
            enterRequestContext();
            runOn(automation);
            clean(); // the next submitter has no context at all
            assertEquals(new Seen(null, null, null, null), runOn(automation));
        } finally { automation.shutdown(); }
    }

    @Test
    void broadcastQueueIsBoundedAndRejectsWhenFull() throws Exception {
        ThreadPoolTaskExecutor broadcast = new AsyncConfig().broadcastExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 5 + AsyncConfig.BROADCAST_QUEUE_CAPACITY; i++) {
                broadcast.submit(() -> { try { release.await(); } catch (InterruptedException ignored) { } });
            }
            assertEquals(0, broadcast.getThreadPoolExecutor().getQueue().remainingCapacity());
            assertThrows(TaskRejectedException.class, () -> broadcast.submit(() -> { }));
        } finally {
            release.countDown();
            broadcast.shutdown();
        }
    }
}
