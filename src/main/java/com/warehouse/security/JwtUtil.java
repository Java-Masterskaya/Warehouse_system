package com.warehouse.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;

    @Getter
    private final long expirationMs;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username, List<String> roles) {
        log.debug("Generating JWT for user: {}", username);
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        String token = Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        log.debug("JWT generated successfully, expires in {} ms", expirationMs);
        return token;
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public List<String> getRolesFromToken(String token) {
        return extractRoles(token)
                .orElseThrow(() -> new JwtException("Invalid or missing 'roles' claim"));
    }

    public boolean validateToken(String token) {
        try {
            return extractRoles(token).isPresent();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Optional<List<String>> extractRoles(String token) {
        Claims claims = parseClaims(token);
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return Optional.of(list.stream().map(String.class::cast).toList());
        }
        return Optional.empty();
    }
}
