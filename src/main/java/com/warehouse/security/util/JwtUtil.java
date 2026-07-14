package com.warehouse.security.util;

import com.warehouse.security.model.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Утилитный класс для работы с JWT токенами.
 * <p>
 * Предоставляет функционал для генерации и валидации access и refresh токенов.
 * Access токены имеют короткое время жизни (по умолчанию 1 день),
 * refresh токены - длинное (по умолчанию 7 дней).
 * </p>
 *
 */
@Slf4j
@Component
@RefreshScope
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    @Getter
    @Value("${app.jwt.expiration-ms:86400000}") // access token expiration / 1 day
    private long expirationMs;

    @Getter
    @Value("${app.jwt.refresh-expiration-ms:604800000}") // refresh token expiration / 7 days
    private long refreshExpirationMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        log.info("🔐 JwtUtil initialized with expirationMs: {}, refreshExpirationMs: {}",
                expirationMs, refreshExpirationMs);
    }

    public String generateToken(String username, Long userId, List<String> roles) {
        return generateToken(username, userId, roles, expirationMs, TokenType.ACCESS);
    }

    public String generateRefreshToken(String username, Long userId, List<String> roles) {
        return generateToken(username, userId, roles, refreshExpirationMs, TokenType.REFRESH);
    }

    private String generateToken(String username, Long userId, List<String> roles, long expiration, TokenType tokenType) {
        log.debug("Generating token for user: {}", username);
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expiration);

        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", roles)
                .claim("tokenType", tokenType.name())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        log.debug("Token generated successfully for user: {} expires in {} ms", username, expiration);
        return token;
    }

    public Optional<JwtPayload> parseAccessToken(String token) {
        return parseTokenInternal(token, TokenType.ACCESS);
    }

    public Optional<JwtPayload> parseRefreshToken(String token) {
        return parseTokenInternal(token, TokenType.REFRESH);
    }

    private Optional<JwtPayload> parseTokenInternal(String token, TokenType expectedType) {
        try {
            Claims claims = parseClaims(token);
            // Проверяем тип токена
            String tokenType = claims.get("tokenType", String.class);
            if (!expectedType.name().equals(tokenType)) {
                log.warn("Invalid token type: expected {}, got {}", expectedType, tokenType);
                return Optional.empty();
            }
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

    public long getTokenRemainingTime(String token) {
        try {
            Claims claims = parseClaims(token);
            Date expiration = claims.getExpiration();
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(0, remaining);
        } catch (Exception e) {
            return 0;
        }
    }

    public record JwtPayload(Long userId, String username, List<String> roles) {
    }
}
