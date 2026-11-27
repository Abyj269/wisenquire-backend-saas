package com.leads.config;

import com.leads.model.entity.User;
import com.leads.repository.UserRepository;
import com.leads.service.TenantAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.User.UserBuilder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Plan Phase 0 (Step 3.1): STOMP frames set TenantContext for their own thread and never leak it. */
class StompTenantContextTest {
    JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    CustomUserDetailsService details = mock(CustomUserDetailsService.class);
    UserRepository users = mock(UserRepository.class);
    StompSecurityInterceptor interceptor = new StompSecurityInterceptor(tokens, details, users, mock(TenantAccess.class));
    MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach void validToken() {
        when(tokens.validateToken("tok")).thenReturn(true);
        when(tokens.getSubject("tok")).thenReturn(new JwtTokenProvider.Subject(null, "agent@example.test")); // module 02 P2
        when(tokens.getSessionVersion("tok")).thenReturn(0L);
        when(tokens.getContextClaims("tok")).thenReturn(new TenantContext.Data(11L, 4L, "BRANCH"));
        UserBuilder principal = org.springframework.security.core.userdetails.User.withUsername("agent@example.test").password("x").authorities(List.of());
        when(details.toUserDetails(org.mockito.ArgumentMatchers.any(User.class))).thenReturn(principal.build());
        User agent = User.builder().email("agent@example.test").sessionVersion(0L).build();
        when(users.findForAuthenticationByEmail("agent@example.test")).thenReturn(Optional.of(agent));
    }
    @AfterEach void reset() { TenantContext.clear(); }

    Message<byte[]> frame(StompCommand command) {
        var h = StompHeaderAccessor.create(command);
        h.setSessionId("s1");
        if (command == StompCommand.CONNECT) h.setNativeHeader("Authorization", "Bearer tok");
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    @Test void connectSetsContextAndCompletionClearsIt() {
        var msg = interceptor.preSend(frame(StompCommand.CONNECT), channel);
        assertEquals(4L, TenantContext.get().orElseThrow().organizationId());
        interceptor.afterSendCompletion(msg, channel, true, null);
        assertTrue(TenantContext.get().isEmpty());
    }

    @Test void deniedFrameDoesNotLeakContext() {
        interceptor.preSend(frame(StompCommand.CONNECT), channel);
        interceptor.afterSendCompletion(null, channel, true, null);
        // SEND is always denied; Spring does not call afterSendCompletion when preSend throws.
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(StompCommand.SEND), channel));
        assertTrue(TenantContext.get().isEmpty());
    }

    @Test void outboundNeverTouchesTheSendersContext() {
        interceptor.preSend(frame(StompCommand.CONNECT), channel);   // registers session s1
        interceptor.afterSendCompletion(null, channel, true, null);
        var requestContext = new TenantContext.Data(99L, 8L, "COMPANY");   // an HTTP request thread pushing a message
        TenantContext.set(requestContext);
        var out = MessageBuilder.withPayload(new byte[0]).setHeader("simpSessionId", "s1").build();
        assertNotNull(interceptor.outbound().preSend(out, channel));
        assertEquals(requestContext, TenantContext.get().orElseThrow());
    }
}
