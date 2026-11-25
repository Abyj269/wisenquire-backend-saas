package com.leads.service;

import com.leads.dto.tenant.RolePermissionDto;
import com.leads.exception.ApiException;
import com.leads.exception.ErrorCode;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.DataScope;
import com.leads.model.enums.RoleLevel;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Module 03 P1 unit rules: organization slugs, the D15 timezone allow-list, and the D14 role-scope rule. */
class TenantAdminP1Test {

    // ---- slugs (V65 uses the same rule in SQL) ---------------------------------------------------

    @Test
    void slugsAreLowerCaseDashSeparatedAndNeverEmpty() {
        assertEquals("acme-motors", OrganizationSlugs.slugify("ACME_Motors"));
        assertEquals("al-noor-trading", OrganizationSlugs.slugify("  Al Noor -- Trading!! "));
        assertEquals("org", OrganizationSlugs.slugify("***"));
        assertEquals("org", OrganizationSlugs.slugify(null));
        assertTrue(OrganizationSlugs.slugify("x".repeat(200)).length() <= 60);
    }

    @Test
    void aTakenSlugGetsTheNextFreeNumber() {
        Set<String> taken = Set.of("acme", "acme-2");
        assertEquals("acme-3", OrganizationSlugs.unique("ACME", taken::contains));
        assertEquals("beta", OrganizationSlugs.unique("Beta", taken::contains));
    }

    // ---- D15 allow-list ------------------------------------------------------------------------

    private static CompanyTimezones timezones(boolean flagOn) {
        FeatureFlags flags = mock(FeatureFlags.class);
        when(flags.isOn(FeatureFlag.TENANT_TIMEZONE_ALLOWLIST, 7L)).thenReturn(flagOn);
        return new CompanyTimezones(flags);
    }

    @Test
    void withTheFlagOnOnlyTheSevenZonesPass() {
        CompanyTimezones tz = timezones(true);
        for (String zone : CompanyTimezones.ALLOWED) assertDoesNotThrow(() -> tz.check(7L, zone));
        for (String zone : List.of("Europe/London", "Asia/Karachi", "UTC", "Asia/Calcutta")) {
            ApiException e = assertThrows(ApiException.class, () -> tz.check(7L, zone), zone);
            assertEquals(ErrorCode.VALIDATION_FAILED, e.getCode());
            assertEquals(CompanyTimezones.ALLOWED, e.getDetails().get("allowed"));
        }
        assertDoesNotThrow(() -> tz.check(7L, null), "null = timezone not being changed");
    }

    @Test
    void withTheFlagOffAnyZonePassesAsBefore() {
        assertDoesNotThrow(() -> timezones(false).check(7L, "Europe/London"));
        assertEquals(7, CompanyTimezones.ALLOWED.size());
    }

    // ---- D14: requested data scope -------------------------------------------------------------

    private static DelegationPolicy policyFor(DataScope actorScope) {
        TenantAccess access = mock(TenantAccess.class);
        User actor = User.builder().id(1L).role(Role.builder().id(10L).roleLevel(RoleLevel.BRANCH).dataScope(actorScope).build()).build();
        when(access.actor()).thenReturn(actor);
        when(access.isSuper(any())).thenReturn(false);
        return new DelegationPolicy(access);
    }

    private static final List<RolePermissionDto> NONE = List.of();

    @Test
    void anOwnCreatorCannotMakeOrWidenALevelRole() {
        DelegationPolicy own = policyFor(DataScope.OWN);
        assertThrows(AccessDeniedException.class, () -> own.role(5L, RoleLevel.BRANCH, NONE, null, DataScope.LEVEL));
        assertThrows(AccessDeniedException.class, () -> own.role(5L, RoleLevel.BRANCH, NONE, null, null), "null on create = LEVEL");
        Role ownRole = Role.builder().id(20L).roleLevel(RoleLevel.BRANCH).dataScope(DataScope.OWN).isSystemRole(false).build();
        assertThrows(AccessDeniedException.class, () -> own.role(5L, RoleLevel.BRANCH, NONE, ownRole, DataScope.LEVEL), "widening OWN -> LEVEL");
        assertDoesNotThrow(() -> own.role(5L, RoleLevel.BRANCH, NONE, null, DataScope.OWN));
        assertDoesNotThrow(() -> own.role(5L, RoleLevel.BRANCH, NONE, ownRole, null), "null on update = unchanged (OWN)");
    }

    @Test
    void aLevelCreatorMayChooseEitherScope() {
        DelegationPolicy level = policyFor(DataScope.LEVEL);
        assertDoesNotThrow(() -> level.role(5L, RoleLevel.BRANCH, NONE, null, DataScope.LEVEL));
        assertDoesNotThrow(() -> level.role(5L, RoleLevel.BRANCH, NONE, null, DataScope.OWN));
    }
}
