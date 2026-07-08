package com.warehouse.security.service;

import com.warehouse.security.JwtUtil;
import com.warehouse.security.model.TokenPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String USER_TOKENS_PREFIX = "user:tokens:";
    private static final String REFRESH_ROTATION_PREFIX = "rotation:";

    @Override
    public TokenPair generateTokenPair(String username, Long userId, List<String> roles) {
        log.info("Generating token pair for user: username='{}', userId={}, roles={}",
                username, userId, roles);
        String accessToken = generateAccessToken(username, userId, roles);
        String refreshToken = generateRefreshToken(username, userId, roles);

        // Store refresh token in Redis with TTL
        storeRefreshToken(refreshToken, userId);
        log.info("Token pair generated successfully for user: userId={}", userId);
        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public String generateAccessToken(String username, Long userId, List<String> roles) {
        log.debug("Generating access token for user: username='{}', userId={}", username, userId);
        return jwtUtil.generateToken(username, userId, roles);
    }

    @Override
    public String generateRefreshToken(String username, Long userId, List<String> roles) {
        log.debug("Generating refresh token for user: username='{}', userId={}", username, userId);
        // Refresh token with longer TTL (e.g., 7 days)
        return jwtUtil.generateRefreshToken(username, userId, roles);
    }

    @Override
    public boolean validateRefreshToken(String refreshToken) {
        log.info("Start the validation of the refresh token");
        try {
            var payload = jwtUtil.parseRefreshToken(refreshToken);
            if (payload.isEmpty()) {
                log.warn("Refresh token validation failed: invalid payload");
                return false;
            }

            // Check if token is revoked
            String key = REFRESH_PREFIX + refreshToken;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        log.info("Start the revoke of the refresh token");
        String key = REFRESH_PREFIX + refreshToken;
        redisTemplate.delete(key);
        log.info("Refresh token revoked: {}", refreshToken);
    }

    @Override
    public void revokeAllUserTokens(Long userId) {
        log.info("Revoking all tokens for user: userId={}", userId);
        String pattern = USER_TOKENS_PREFIX + userId + ":*";
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("All tokens revoked for user: {}", userId);
        }
    }

    @Override
    public void rotateRefreshToken(String oldRefreshToken, String newRefreshToken) {
        log.info("Start of rotating refresh token");
        // Get user info from old token
        var oldPayload = jwtUtil.parseRefreshToken(oldRefreshToken);
        oldPayload.ifPresent(payload -> {
            // Store rotation info to detect reuse
            String rotationKey = REFRESH_ROTATION_PREFIX + oldRefreshToken;
            redisTemplate.opsForValue().set(rotationKey, "rotated",
                    Duration.ofMinutes(5)); // Short TTL for rotation detection
            // Revoke old token
            revokeRefreshToken(oldRefreshToken);
            // Store new token
            storeRefreshToken(newRefreshToken, payload.userId());
            log.info("Refresh token rotated for user: {}", payload.userId());
        });
    }

    @Override
    public boolean isTokenBlacklisted(String accessToken) {
        log.info("Checking if token is blacklisted");
        String key = BLACKLIST_PREFIX + accessToken;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void blacklistAccessToken(String accessToken) {
        log.info("Adding token to blacklist");
        try {
            var payload = jwtUtil.parseToken(accessToken);
            payload.ifPresent(p -> {
                long ttl = jwtUtil.getTokenRemainingTime(accessToken);
                String key = BLACKLIST_PREFIX + accessToken;
                redisTemplate.opsForValue().set(key, "blacklisted", ttl, TimeUnit.MILLISECONDS);
                log.debug("Access token blacklisted for user: {}", p.userId());
            });
        } catch (Exception e) {
            log.warn("Failed to blacklist token: {}", e.getMessage());
        }
    }

    private void storeRefreshToken(String refreshToken, Long userId) {
        log.debug("Storing refresh token in Redis: userId={}", userId);
        // Store refresh token
        String key = REFRESH_PREFIX + refreshToken;
        long ttl = jwtUtil.getRefreshExpirationMs();
        redisTemplate.opsForValue().set(key, userId.toString(), ttl, TimeUnit.MILLISECONDS);

        // Link token to user for easy revocation
        String userKey = USER_TOKENS_PREFIX + userId + ":" + refreshToken;
        redisTemplate.opsForValue().set(userKey, "active", ttl, TimeUnit.MILLISECONDS);
    }
}
