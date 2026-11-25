package com.leads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * First use of {@code @Scheduled}/{@code @Async} in this codebase — everything
 * else here is request/webhook-driven. Backs the Ads module's periodic
 * ad/spend sync (AdSyncService) and its one-off historical backfill, which
 * can run long enough (years of daily data) that it must not block the
 * connection-save HTTP request. A dedicated bounded executor is used rather
 * than Spring's default unbounded SimpleAsyncTaskExecutor, which spawns an
 * unlimited number of threads under load.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {

    @Bean(name = "adsSyncExecutor")
    public ThreadPoolTaskExecutor adsSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ads-sync-");
        executor.setTaskDecorator(new TenantTaskDecorator()); // 01 Part B item 6
        executor.initialize();
        return executor;
    }

    /**
     * WhatsApp automations run off the webhook thread so Meta gets its 200
     * immediately. Separate from the ads pool so a long backfill can't starve
     * customer replies (and vice versa). A short WAIT action holds a thread
     * for up to AutomationDefinition.MAX_INLINE_WAIT_SECONDS.
     */
    @Bean(name = "automationExecutor")
    public ThreadPoolTaskExecutor automationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("automation-");
        executor.setTaskDecorator(new TenantTaskDecorator()); // 01 Part B item 6
        executor.initialize();
        return executor;
    }
}
