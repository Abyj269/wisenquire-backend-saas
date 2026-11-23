package com.leads.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@lombok.RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompSecurityInterceptor security;
    @org.springframework.beans.factory.annotation.Value("${app.security.allowed-origins:http://localhost:4201}")
    private String[] origins;

    @Override public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) { registration.interceptors(security); }
    @Override public void configureClientOutboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) { registration.interceptors(security.outbound()); }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(origins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
