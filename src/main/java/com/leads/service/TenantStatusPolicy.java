package com.leads.service;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;
import com.leads.model.entity.Organization;
import com.leads.model.enums.OrganizationStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 01 Part B item 2 / D12: what an organization's status allows. Reads are always allowed (so a
 * tenant can still see and export its data); writes are blocked:
 * <pre>
 *   SUSPENDED, CANCELLED           -> 423 TENANT_SUSPENDED
 *   PAST_DUE past the grace period -> 402 TENANT_PAST_DUE   (grace from status_changed_at)
 *   TRIAL past trial_ends_at       -> 402 TENANT_TRIAL_EXPIRED
 * </pre>
 * Write vs read comes from the current HTTP method (GET/HEAD/OPTIONS = read). Code running without
 * an HTTP request counts as a write. Jobs call {@link #enforce(Organization, boolean)} with an
 * explicit flag. Every existing organization is ACTIVE (V51), so this blocks nothing until modules
 * 20/21/22 set another status.
 */
@Component
public class TenantStatusPolicy {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final int pastDueGraceDays;

    public TenantStatusPolicy(@Value("${app.tenant.past-due-grace-days:7}") int pastDueGraceDays) {
        this.pastDueGraceDays = pastDueGraceDays;
    }

    /** For request threads: write vs read from the HTTP method. */
    public void enforce(Organization org) { enforce(org, isWriteRequest()); }

    public void enforce(Organization org, boolean write) {
        if (!write || org == null) return;
        OrganizationStatus status = org.getStatus() == null ? OrganizationStatus.ACTIVE : org.getStatus();
        LocalDateTime now = LocalDateTime.now(); // timestamp comparison, not a calendar decision
        switch (status) {
            case SUSPENDED, CANCELLED -> throw new ApiException(ErrorCode.TENANT_SUSPENDED,
                    "This account is suspended. You can still view and export your data; contact billing to restore it.");
            case PAST_DUE -> {
                LocalDateTime since = org.getStatusChangedAt();
                if (since == null || !now.isBefore(since.plusDays(pastDueGraceDays))) {
                    throw new ApiException(ErrorCode.TENANT_PAST_DUE, "Payment is overdue. Changes are paused until the account is paid.");
                }
            }
            case TRIAL -> {
                if (org.getTrialEndsAt() != null && !now.isBefore(org.getTrialEndsAt())) {
                    throw new ApiException(ErrorCode.TENANT_TRIAL_EXPIRED, "Your trial has ended. Choose a plan to continue making changes.");
                }
            }
            case ACTIVE -> { }
        }
    }

    static boolean isWriteRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return true;
        HttpServletRequest request = attrs.getRequest();
        return !READ_METHODS.contains(request.getMethod());
    }
}
