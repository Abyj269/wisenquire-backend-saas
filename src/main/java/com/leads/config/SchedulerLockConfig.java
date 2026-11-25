package com.leads.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 01 Part B item 7: ShedLock on the shared database (V53 table {@code shedlock}), so each @Scheduled
 * job runs on one instance at a time. Uses the database clock, so instance clock drift does not
 * matter. Kill switch: {@code app.shedlock.enabled=false} (then the @SchedulerLock annotations do
 * nothing and every instance runs every job, as before).
 */
@Configuration
@ConditionalOnProperty(name = "app.shedlock.enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .usingDbTime()
                .build());
    }
}
