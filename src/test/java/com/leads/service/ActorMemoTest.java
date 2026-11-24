package com.leads.service;

import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.LeadRepository;
import com.leads.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 01 Part B item 1: actor() reads the memo; clear() forces a re-read; the kill switch disables it. */
class ActorMemoTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TenantAccess access = new TenantAccess(mock(EntityManager.class), users, mock(LeadRepository.class), new TenantStatusPolicy(7));

    private static User user(String email, long version) {
        return User.builder().email(email).sessionVersion(version).isActive(true)
                .role(Role.builder().roleName("r").roleLevel(RoleLevel.BRANCH).build()).build();
    }

    private static void login(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @AfterEach
    void cleanUp() { ActorMemo.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void aMemoHitAvoidsTheQuery() {
        login("a@x.test");
        User memo = user("a@x.test", 1);
        ActorMemo.put("a@x.test", memo);
        assertSame(memo, access.actor());
        assertSame(memo, access.actor());
        verifyNoInteractions(users);
    }

    @Test
    void clearForcesAReReadWithTheNewSessionVersion() {
        login("a@x.test");
        ActorMemo.put("a@x.test", user("a@x.test", 1));
        ActorMemo.clear(); // what TenantManagementService does after bumping sessionVersion
        when(users.findByEmail("a@x.test")).thenReturn(Optional.of(user("a@x.test", 2)));
        assertEquals(2L, access.actor().getSessionVersion());
        verify(users).findByEmail("a@x.test");
    }

    @Test
    void aMemoForAnotherEmailIsIgnored() {
        login("b@x.test");
        ActorMemo.put("a@x.test", user("a@x.test", 1));
        when(users.findByEmail("b@x.test")).thenReturn(Optional.of(user("b@x.test", 1)));
        assertEquals("b@x.test", access.actor().getEmail());
    }

    @Test
    void actorNeverFillsTheMemoItself() {
        login("a@x.test");
        when(users.findByEmail("a@x.test")).thenReturn(Optional.of(user("a@x.test", 1)));
        access.actor();
        assertNull(ActorMemo.get("a@x.test"), "only the JWT filter / STOMP interceptor fill it (pooled threads)");
    }

    @Test
    void killSwitchIgnoresTheMemo() {
        ReflectionTestUtils.setField(access, "memoEnabled", false);
        login("a@x.test");
        ActorMemo.put("a@x.test", user("a@x.test", 1));
        when(users.findByEmail("a@x.test")).thenReturn(Optional.of(user("a@x.test", 9)));
        assertEquals(9L, access.actor().getSessionVersion());
    }
}
