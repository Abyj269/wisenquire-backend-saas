package com.leads.config;

import com.leads.model.entity.Organization;
import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.RoleLevel;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Plan Phase 0 (Step 3.1): TenantContext + MDC are set from the JWT and always cleared. */
class TenantContextAndTraceTest {

    @AfterEach void reset() { TenantContext.clear(); MDC.clear(); }

    @Test void setAndClearMaintainMdc() {
        TenantContext.set(new TenantContext.Data(7L, 3L, "BRANCH"));
        assertEquals("7", MDC.get("userId"));
        assertEquals("3", MDC.get("orgId"));
        assertEquals("BRANCH", TenantContext.get().orElseThrow().roleLevel());
        TenantContext.clear();
        assertTrue(TenantContext.get().isEmpty());
        assertNull(MDC.get("userId"));
        assertNull(MDC.get("orgId"));
    }

    @Test void orgLessPlatformAdminHasNoOrgKey() {
        TenantContext.set(new TenantContext.Data(1L, null, "SUPER_ADMIN"));
        assertEquals("1", MDC.get("userId"));
        assertNull(MDC.get("orgId"));
    }

    @Test void traceFilterSetsHeaderAndClearsEverythingAfterTheRequest() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest("GET", "/leads");
        request.addHeader("X-Request-Id", "client-trace-0001");
        var response = new MockHttpServletResponse();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seenTrace.set(MDC.get("traceId"));
            TenantContext.set(new TenantContext.Data(5L, 2L, "COMPANY"));   // as JwtAuthenticationFilter would
        };
        filter.doFilter(request, response, chain);
        assertEquals("client-trace-0001", seenTrace.get());
        assertEquals("client-trace-0001", response.getHeader("X-Request-Id"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("orgId"));
        assertTrue(TenantContext.get().isEmpty(), "a pooled thread must not carry the tenant into the next request");
    }

    @Test void traceFilterClearsEvenWhenTheRequestFails() {
        var filter = new RequestTraceFilter();
        FilterChain chain = (req, res) -> {
            TenantContext.set(new TenantContext.Data(5L, 2L, "COMPANY"));
            throw new IllegalStateException("boom");
        };
        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain));
        assertTrue(TenantContext.get().isEmpty());
        assertNull(MDC.get("traceId"));
    }

    @Test void unsafeIncomingTraceIdIsReplaced() {
        assertEquals("abcdef12", RequestTraceFilter.resolveTraceId("abcdef12"));
        String injected = RequestTraceFilter.resolveTraceId("x\nFAKE LOG LINE");
        assertNotEquals("x\nFAKE LOG LINE", injected);
        assertTrue(injected.matches("[0-9a-f-]{36}"));
        assertTrue(RequestTraceFilter.resolveTraceId(null).matches("[0-9a-f-]{36}"));
        assertTrue(RequestTraceFilter.resolveTraceId("short").matches("[0-9a-f-]{36}"));
    }

    @Test void jwtContextClaimsComeFromTheSignedToken() {
        var provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret",
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 60_000L);
        var role = Role.builder().roleName("Agent").roleLevel(RoleLevel.BRANCH).build();
        var withOrg = User.builder().id(42L).email("a@example.test").role(role).isSuperAdmin(false)
                .organization(Organization.builder().id(9L).build()).build();
        var data = provider.getContextClaims(provider.generateToken(withOrg));
        assertEquals(42L, data.userId());
        assertEquals(9L, data.organizationId());
        assertEquals("BRANCH", data.roleLevel());

        var platform = User.builder().id(1L).email("p@example.test").isSuperAdmin(true)
                .role(Role.builder().roleName("Super Admin").roleLevel(RoleLevel.SUPER_ADMIN).build()).build();
        assertNull(provider.getContextClaims(provider.generateToken(platform)).organizationId());
    }
}
