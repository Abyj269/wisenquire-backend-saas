package com.leads.entitlement;

/**
 * D7: plan checks, called in services and jobs. The organization is always passed explicitly
 * (from TenantAccess / the job's own record), never taken from a request body.
 * <ul>
 *   <li>{@link #require}: throws {@link FeatureNotInPlanException} (403 FEATURE_NOT_IN_PLAN).</li>
 *   <li>{@link #check}: throws {@link EntitlementLimitException} (409 ENTITLEMENT_LIMIT) if adding
 *       {@code n} would exceed the limit; changes nothing.</li>
 *   <li>{@link #consume} / {@link #release}: reserve and give back {@code n} units. Implementations
 *       must not hold a row lock across an external call (CLAUDE.md rule 2).</li>
 * </ul>
 * Until module 22 the only implementation is {@link UnlimitedEntitlements}.
 */
public interface EntitlementService {
    void require(long organizationId, Feature feature);
    void check(long organizationId, Metric metric, long n);
    void consume(long organizationId, Metric metric, long n);
    void release(long organizationId, Metric metric, long n);
}
