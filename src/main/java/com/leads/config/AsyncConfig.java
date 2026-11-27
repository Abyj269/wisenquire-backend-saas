package com.leads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /** Same size as before (5 threads). The queue is now bounded (was unbounded). */
    public static final int BROADCAST_QUEUE_CAPACITY = 1000;

    /**
     * 01 Part B item 6: was a raw {@code Executors.newFixedThreadPool(5)} with an unbounded queue and
     * no context. Now a bounded ThreadPoolTaskExecutor with the TenantTaskDecorator. When the queue
     * is full a submit is rejected (TaskRejectedException) instead of growing without limit;
     * BroadcastServiceImpl turns that into a 409 CONFLICT "try again shortly".
     */
    @Bean(name = "broadcastExecutor")
    public ThreadPoolTaskExecutor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(BROADCAST_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("broadcast-");
        executor.setTaskDecorator(new TenantTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
