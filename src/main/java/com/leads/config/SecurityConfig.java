package com.leads.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}")
    private int managementPort;

    // Same properties (and springdoc's own defaults) that switch the endpoints on/off.
    @org.springframework.beans.factory.annotation.Value("${springdoc.swagger-ui.enabled:true}")
    private boolean swaggerUiEnabled;

    @org.springframework.beans.factory.annotation.Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    private static final org.springframework.util.AntPathMatcher PATHS = new org.springframework.util.AntPathMatcher();

    /** Matches the path inside the servlet context, the same view the string matchers above use. */
    private static boolean matchesAny(jakarta.servlet.http.HttpServletRequest request, String... patterns) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String p : patterns) {
            if (PATHS.match(p, path)) return true;
        }
        return false;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Both fire before the request ever reaches a controller (no/invalid
                // JWT, or a URL-level authorization rule) — without these, Spring
                // Security's own defaults return a blank or plain-text body, which is
                // where the frontend's raw "403 OK"-style fallback text was coming
                // from. GlobalExceptionHandler covers the same two cases for
                // @PreAuthorize denials thrown from inside a controller method; this
                // covers denials from the filter chain itself.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            boolean revoked = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.SESSION_REVOKED_ATTRIBUTE));
                            writeJsonError(response, revoked ? com.leads.exception.ErrorCode.SESSION_REVOKED : com.leads.exception.ErrorCode.UNAUTHENTICATED,
                                    revoked ? "Your session has ended. Please log in again." : "Please log in to continue.");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeJsonError(response, com.leads.exception.ErrorCode.FORBIDDEN, "You don't have permission to do this."))
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints - listed both with and without the
                        // "/api/v1" prefix. In local/embedded-Tomcat dev mode,
                        // server.servlet.context-path is applied at the
                        // servlet-container level, so both MVC routing and
                        // these security matchers see the bare "/auth/login"
                        // form. Deployed as a WAR under an external Tomcat's
                        // own context (e.g. astra_crm_live.war -> context
                        // "/astra_crm_live", stacked with this app's own
                        // server.servlet.context-path=/api/v1), MVC routing
                        // still resolves to the bare form, but these request
                        // matchers only see Tomcat's own context path
                        // stripped - the "/api/v1" segment is still present
                        // from their point of view. Listing both covers both
                        // deployment modes.
                        .requestMatchers(
                                "/auth/login", "/api/v1/auth/login",
                                "/auth/verify-otp", "/api/v1/auth/verify-otp",
                                // "/auth/refresh" removed (F-7): no such endpoint exists until module 02 adds it.
                                "/whatsapp/webhook", "/api/v1/whatsapp/webhook",
                                // Liveness/readiness probes (Actuator). Only "health" is exposed by
                                // default (application.yml), and it shows no details.
                                "/actuator/health", "/actuator/health/**",
                                "/api/v1/actuator/health", "/api/v1/actuator/health/**",
                                "/ws/**", "/api/v1/ws/**"
                        ).permitAll()
                        // F-7: the API map is public only where springdoc is on (windows-dev).
                        // With it off (prod), these paths fall to anyRequest().authenticated()
                        // and an anonymous caller gets 401 instead of reaching MVC at all.
                        .requestMatchers(request -> swaggerUiEnabled && matchesAny(request,
                                "/swagger-ui/**", "/api/v1/swagger-ui/**",
                                "/swagger-ui.html", "/api/v1/swagger-ui.html")).permitAll()
                        .requestMatchers(request -> apiDocsEnabled && matchesAny(request,
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/api/v1/v3/api-docs", "/api/v1/v3/api-docs/**")).permitAll()
                        // Container only: the separate management port (management.server.port,
                        // e.g. 9090) serves health + Prometheus metrics to the internal scraper.
                        // That port must never be published to the internet. Off (-1) for the WAR.
                        .requestMatchers(request -> managementPort > 0 && request.getLocalPort() == managementPort).permitAll()
                        // Any other request must be authenticated
                        .anyRequest().authenticated()
                );

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** D12 body for errors raised by the filter chain itself (before any controller). */
    private void writeJsonError(HttpServletResponse response, com.leads.exception.ErrorCode code, String message) throws IOException {
        response.setStatus(code.status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(com.leads.exception.ErrorBodies.json(code, message));
    }
}
