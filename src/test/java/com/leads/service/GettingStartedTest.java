package com.leads.service;
import com.leads.controller.AuthController;
import com.leads.model.entity.User;
import com.leads.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GettingStartedTest {
    @Test void welcomeSeenIsAccountSpecificAndIdempotent() {
        var access = mock(TenantAccess.class); var users = mock(UserRepository.class);
        var user = User.builder().id(42L).build(); when(access.actor()).thenReturn(user);
        var controller = new AuthController(null,access,users,null);
        assertFalse(user.isGettingStartedSeen());
        controller.markGettingStartedSeen(); controller.markGettingStartedSeen();
        assertTrue(user.isGettingStartedSeen()); verify(users,times(2)).save(user);
    }
    @Test void unauthenticatedActorCannotMarkAUserSeen() {
        var access = mock(TenantAccess.class); var users = mock(UserRepository.class);
        when(access.actor()).thenThrow(new AccessDeniedException("Denied"));
        assertThrows(AccessDeniedException.class,()->new AuthController(null,access,users,null).markGettingStartedSeen());
        verifyNoInteractions(users);
    }
}
