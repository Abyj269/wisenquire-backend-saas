package com.leads.service;

import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;
import com.leads.model.entity.Organization;
import com.leads.model.enums.OrganizationStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 01 Part B item 2: what each organization status allows (reads always; writes per status). */
class TenantStatusPolicyTest {

    private final TenantStatusPolicy policy = new TenantStatusPolicy(7);

    private static Organization org(OrganizationStatus status, LocalDateTime changed, LocalDateTime trialEnds) {
        Organization o = Organization.builder().name("x").code("x").build();
        o.setStatus(status);
        o.setStatusChangedAt(changed);
        o.setTrialEndsAt(trialEnds);
        return o;
    }

    private void blocked(Organization o, ErrorCode code) {
        ApiException ex = assertThrows(ApiException.class, () -> policy.enforce(o, true));
        assertEquals(code, ex.getCode());
        assertDoesNotThrow(() -> policy.enforce(o, false), "reads stay allowed");
    }

    @Test void activeAllowsEverything() { assertDoesNotThrow(() -> policy.enforce(org(OrganizationStatus.ACTIVE, null, null), true)); }

    @Test void nullStatusCountsAsActive() { assertDoesNotThrow(() -> policy.enforce(org(null, null, null), true)); }

    @Test void suspendedAndCancelledBlockWrites() {
        blocked(org(OrganizationStatus.SUSPENDED, LocalDateTime.now(), null), ErrorCode.TENANT_SUSPENDED);
        blocked(org(OrganizationStatus.CANCELLED, LocalDateTime.now(), null), ErrorCode.TENANT_SUSPENDED);
    }

    @Test void pastDueWithinGraceAllowsWrites() {
        assertDoesNotThrow(() -> policy.enforce(org(OrganizationStatus.PAST_DUE, LocalDateTime.now().minusDays(6), null), true));
    }

    @Test void pastDueAfterGraceBlocksWrites() {
        blocked(org(OrganizationStatus.PAST_DUE, LocalDateTime.now().minusDays(7).minusMinutes(1), null), ErrorCode.TENANT_PAST_DUE);
        blocked(org(OrganizationStatus.PAST_DUE, null, null), ErrorCode.TENANT_PAST_DUE); // unknown start = no grace
    }

    @Test void trialBlocksWritesOnlyAfterItEnds() {
        assertDoesNotThrow(() -> policy.enforce(org(OrganizationStatus.TRIAL, null, LocalDateTime.now().plusDays(3)), true));
        assertDoesNotThrow(() -> policy.enforce(org(OrganizationStatus.TRIAL, null, null), true));
        blocked(org(OrganizationStatus.TRIAL, null, LocalDateTime.now().minusMinutes(1)), ErrorCode.TENANT_TRIAL_EXPIRED);
    }

    @Test void codesHaveTheD12Statuses() {
        assertEquals(423, ErrorCode.TENANT_SUSPENDED.status.value());
        assertEquals(402, ErrorCode.TENANT_PAST_DUE.status.value());
        assertEquals(402, ErrorCode.TENANT_TRIAL_EXPIRED.status.value());
    }

    @Test void withoutAnHttpRequestItIsTreatedAsAWrite() { assertTrue(TenantStatusPolicy.isWriteRequest()); }
}
