package com.leads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean(name = "broadcastExecutor", destroyMethod = "shutdown")
    public ExecutorService broadcastExecutor() {
        return Executors.newFixedThreadPool(5);
    }
}
