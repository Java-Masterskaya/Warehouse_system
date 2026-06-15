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

    public String generateToken(String username, Long userId, List<String> roles) {
        log.debug("Generating JWT for user: {}", username);
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        String token = Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        log.debug("JWT generated successfully for user: {} expires in {} ms", username, expirationMs);
        return token;
    }

    public Optional<JwtPayload> parseToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String username = claims.getSubject();
            Long userId = claims.get("userId", Long.class);
            Object rolesObj = claims.get("roles");

            if (userId != null && rolesObj instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) {
                List<String> roles = list.stream()
                        .map(Object::toString)
                        .toList();
                return Optional.of(new JwtPayload(userId, username, roles));
            }
            log.warn("Invalid or missing claims in token: userId={}, roles={}", userId, rolesObj);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record JwtPayload(Long userId, String username, List<String> roles) {
    }
}
