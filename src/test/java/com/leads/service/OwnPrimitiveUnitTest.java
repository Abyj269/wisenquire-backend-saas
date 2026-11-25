package com.leads.service;

import com.leads.audit.AuditAspect;
import com.leads.config.JwtTokenProvider;
import com.leads.model.entity.Organization;
import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.DataScope;
import com.leads.model.enums.RoleLevel;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 01 Part B items 8 and 9, unit level: DelegationPolicy OWN rule, the dataScope claim, audit masking. */
class OwnPrimitiveUnitTest {

    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private static User user(DataScope scope, RoleLevel level) {
        Role role = Role.builder().id(5L).roleName("r").roleLevel(level).dataScope(scope).build();
        return User.builder().id(9L).email("u@x.test").role(role).isSuperAdmin(false)
                .organization(Organization.builder().id(3L).name("o").code("o").build()).build();
    }

    private static DelegationPolicy policyFor(User actor) {
        TenantAccess access = mock(TenantAccess.class);
        when(access.actor()).thenReturn(actor);
        when(access.isSuper(actor)).thenReturn(false);
        return new DelegationPolicy(access);
    }

    @Test
    void anOwnCreatorCannotCreateOrEditALevelRole() {
        DelegationPolicy policy = policyFor(user(DataScope.OWN, RoleLevel.BRANCH));
        assertThrows(AccessDeniedException.class, () -> policy.role(3L, RoleLevel.BRANCH, List.of(), null));
        Role levelRole = Role.builder().id(77L).roleName("x").roleLevel(RoleLevel.BRANCH).dataScope(DataScope.LEVEL).isSystemRole(false).build();
        assertThrows(AccessDeniedException.class, () -> policy.role(3L, RoleLevel.BRANCH, List.of(), levelRole));
    }

    @Test
    void anOwnCreatorMayEditAnotherOwnRole() {
        DelegationPolicy policy = policyFor(user(DataScope.OWN, RoleLevel.BRANCH));
        Role ownRole = Role.builder().id(77L).roleName("x").roleLevel(RoleLevel.BRANCH).dataScope(DataScope.OWN).isSystemRole(false).build();
        assertDoesNotThrow(() -> policy.role(3L, RoleLevel.BRANCH, List.of(), ownRole));
    }

    @Test
    void aLevelCreatorIsUnchanged() {
        DelegationPolicy policy = policyFor(user(DataScope.LEVEL, RoleLevel.COMPANY));
        assertDoesNotThrow(() -> policy.role(3L, RoleLevel.BRANCH, List.of(), null));
    }

    @Test
    void theJwtCarriesTheDataScopeClaim() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 60_000L);
        for (DataScope scope : DataScope.values()) {
            String token = provider.generateToken(user(scope, RoleLevel.BRANCH));
            Map<String, Object> claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).build()
                    .parseSignedClaims(token).getPayload();
            assertEquals(scope.name(), claims.get("dataScope"));
        }
        User legacy = user(null, RoleLevel.BRANCH); // a role read before V54 has no scope: LEVEL
        String token = provider.generateToken(legacy);
        assertEquals("LEVEL", Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).build()
                .parseSignedClaims(token).getPayload().get("dataScope"));
    }

    @Test
    void auditMasksSecretsAndCutsLongValues() {
        AuditAspect aspect = new AuditAspect(null, mock(org.springframework.transaction.PlatformTransactionManager.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), true);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Branch A");
        body.put("password", "hunter2");
        body.put("accessToken", "EAAG-real-looking");
        body.put("nested", Map.of("appSecret", "s3cret", "apiKey", "k", "note", "ok"));
        body.put("logo", "x".repeat(5000));
        String json = ReflectionTestUtils.invokeMethod(aspect, "masked", body);
        assertNotNull(json);
        for (String secret : new String[]{"hunter2", "EAAG-real-looking", "s3cret", "\"k\""}) assertFalse(json.contains(secret), secret + " leaked: " + json);
        assertTrue(json.contains("\"name\":\"Branch A\""));
        assertTrue(json.contains("\"note\":\"ok\""));
        assertTrue(json.contains("<5000 chars>"));
    }
}
