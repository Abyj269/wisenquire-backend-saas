package com.leads.entitlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default until module 22 (plans and billing): every feature allowed, no limits. */
public class UnlimitedEntitlements implements EntitlementService {
    @Override public void require(long organizationId, Feature feature) { }
    @Override public void check(long organizationId, Metric metric, long n) { }
    @Override public void consume(long organizationId, Metric metric, long n) { }
    @Override public void release(long organizationId, Metric metric, long n) { }

    @Configuration
    static class DefaultEntitlements {
        /** Replaced automatically when module 22 registers its own EntitlementService bean. */
        @Bean
        @ConditionalOnMissingBean(EntitlementService.class)
        EntitlementService unlimitedEntitlements() { return new UnlimitedEntitlements(); }
    }
}
