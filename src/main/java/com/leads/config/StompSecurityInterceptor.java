package com.leads.config;

import com.leads.model.entity.WhatsAppConversation;
import com.leads.repository.UserRepository;
import com.leads.service.TenantAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class StompSecurityInterceptor implements ChannelInterceptor {
    private final JwtTokenProvider tokens;
    private final CustomUserDetailsService details;
    private final UserRepository users;
    private final TenantAccess access;
    private final Map<String,String> sessions = new ConcurrentHashMap<>();

    public UsernamePasswordAuthenticationToken authenticate(String token) {
        if(token==null || !tokens.validateToken(token)) throw denied();
        String email=tokens.getEmailFromToken(token);
        var principal=details.loadUserByUsername(email);
        var user=users.findByEmail(email).orElseThrow(StompSecurityInterceptor::denied);
        if(!principal.isEnabled() || tokens.getSessionVersion(token)!=user.getSessionVersion()) throw denied();
        return new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
    }
    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var h=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(h==null) return message;
        if(h.getCommand()==StompCommand.DISCONNECT) { sessions.remove(h.getSessionId()); return message; }
        if(h.getCommand()==StompCommand.CONNECT) {
            String header=h.getFirstNativeHeader("Authorization");
            String token=header!=null && header.startsWith("Bearer ") ? header.substring(7) : null;
            var auth=authenticate(token);
            if(sessions.size()>10000) throw denied();
            sessions.put(h.getSessionId(),token);h.setUser(auth);return message;
        }
        var auth=authenticate(sessions.get(h.getSessionId()));
        if(h.getCommand()==StompCommand.SEND) throw denied();
        if(h.getCommand()==StompCommand.SUBSCRIBE) {
            var previous=SecurityContextHolder.getContext();
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
            try {
                SecurityContextHolder.setContext(context);
                boolean canRead=auth.getAuthorities().stream().anyMatch(a->java.util.Set.of("ROLE_SUPER_ADMIN","WHATSAPP_LIVE_CHAT:VIEW","LEADS:VIEW","CUSTOMERS:VIEW").contains(a.getAuthority()));
                if(!canRead) throw denied();
                String destination=h.getDestination();
                if(destination==null || !destination.matches("/topic/whatsapp/(company|branch|conversation)/[0-9]+")) throw denied();
                String[] parts=destination.split("/");long id=Long.parseLong(parts[4]);
                switch(parts[3]) {
                    case "company" -> access.companyWide(id);
                    case "branch" -> access.branch(id);
                    case "conversation" -> access.record(WhatsAppConversation.class,id);
                    default -> throw denied();
                }
            } finally { SecurityContextHolder.setContext(previous); }
        }
        return message;
    }
    public ChannelInterceptor outbound() {
        return new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                String id=(String) message.getHeaders().get("simpSessionId");
                if(id!=null && sessions.containsKey(id)) {
                    try { authenticate(sessions.get(id)); } catch(RuntimeException ex) {sessions.remove(id);return null;}
                } else if(id!=null) return null;
                return message;
            }
        };
    }
    @org.springframework.context.event.EventListener
    public void disconnected(org.springframework.web.socket.messaging.SessionDisconnectEvent event) {sessions.remove(event.getSessionId());}
    private static AccessDeniedException denied(){return new AccessDeniedException("WebSocket access denied.");}
}
