package com.leads.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final com.leads.repository.UserRepository users;

    /** Request attribute: the token's session version is stale (D12 SESSION_REVOKED in the 401 body). */
    public static final String SESSION_REVOKED_ATTRIBUTE = "auth.sessionRevoked";

    @org.springframework.beans.factory.annotation.Value("${app.tenant.actor-memo.enabled:true}")
    private boolean memoEnabled = true;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                String email = tokenProvider.getEmailFromToken(jwt);

                // One identity query per request (01 Part B item 1): the same user builds the
                // principal and is memoised for TenantAccess.actor().
                var user = users.findForAuthenticationByEmail(email).orElseThrow();
                UserDetails userDetails = customUserDetailsService.toUserDetails(user);
                boolean sessionCurrent = tokenProvider.getSessionVersion(jwt) == user.getSessionVersion();
                if (!sessionCurrent) request.setAttribute(SESSION_REVOKED_ATTRIBUTE, Boolean.TRUE);
                if (userDetails.isEnabled() && sessionCurrent) {
                    if (memoEnabled) com.leads.service.ActorMemo.put(email, user);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                // Logging/tenant context only; cleared by RequestTraceFilter at request end.
                TenantContext.set(tokenProvider.getContextClaims(jwt));
                }
            }
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            logger.debug("Bearer authentication rejected");
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
