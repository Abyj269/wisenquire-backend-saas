package com.leads.config;

import com.leads.model.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    @Value("${app.security.jwt.expiration-ms}")
    private long jwtExpirationMs;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("sessionVersion", user.getSessionVersion());
        claims.put("email", user.getEmail());
        claims.put("roleName", user.getRole().getRoleName());
        claims.put("roleLevel", user.getRole().getRoleLevel().name());
        // D14: lets the frontend default to "My ..." views and hide assign controls (display only;
        // the server always re-reads the role).
        claims.put("dataScope", (user.getRole().getDataScope() == null ? com.leads.model.enums.DataScope.LEVEL : user.getRole().getDataScope()).name());
        claims.put("isSuperAdmin", user.getIsSuperAdmin());
        if (user.getOrganization() != null) {
            claims.put("organizationId", user.getOrganization().getId());
        }

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .claims(claims)
                .subject(user.getEmail())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public String getEmailFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public long getSessionVersion(String token) {
        Number value = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload().get("sessionVersion", Number.class);
        return value == null ? -1 : value.longValue();
    }

    /** The tenant claims of an already-validated token, for {@link TenantContext} (logging only; never for authorisation). */
    public TenantContext.Data getContextClaims(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        Number userId = claims.get("userId", Number.class);
        Number organizationId = claims.get("organizationId", Number.class);
        return new TenantContext.Data(
                userId == null ? null : userId.longValue(),
                organizationId == null ? null : organizationId.longValue(),
                claims.get("roleLevel", String.class));
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.error("Invalid JWT token: {}", ex.getMessage());
        }
        return false;
    }
}
