package com.leads.service;
import com.leads.controller.AuthController;
import com.leads.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GettingStartedTest {
    /** Phase 1 close: the controller hands the signed-in user to AuthService (no repository in the controller). */
    @Test void welcomeSeenIsAccountSpecificAndIdempotent() {
        var access = mock(TenantAccess.class); var auth = mock(AuthService.class);
        var user = User.builder().id(42L).build(); when(access.actor()).thenReturn(user);
        doAnswer(inv -> { ((User) inv.getArgument(0)).setGettingStartedSeen(true); return null; }).when(auth).markGettingStartedSeen(user);
        var controller = new AuthController(auth,access,null);
        assertFalse(user.isGettingStartedSeen());
        controller.markGettingStartedSeen(); controller.markGettingStartedSeen();
        assertTrue(user.isGettingStartedSeen()); verify(auth,times(2)).markGettingStartedSeen(user);
    }
    @Test void unauthenticatedActorCannotMarkAUserSeen() {
        var access = mock(TenantAccess.class); var auth = mock(AuthService.class);
        when(access.actor()).thenThrow(new AccessDeniedException("Denied"));
        assertThrows(AccessDeniedException.class,()->new AuthController(auth,access,null).markGettingStartedSeen());
        verifyNoInteractions(auth);
    }
}
