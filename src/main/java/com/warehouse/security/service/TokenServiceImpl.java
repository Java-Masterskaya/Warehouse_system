package com.warehouse.security.service;

import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

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
    private static final String USER_ACCESS_PREFIX = "user:access:";

    @Override
    public TokenPair generateTokenPair(String username, Long userId, List<String> roles) {
        log.info("Generating token pair for user: username='{}', userId={}, roles={}",
                username, userId, roles);
        String accessToken = generateAccessToken(username, userId, roles);
        String refreshToken = generateRefreshToken(username, userId, roles);

        // Store refresh token in Redis with TTL
        storeRefreshToken(refreshToken, userId);
        storeAccessToken(accessToken, userId);
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
            // Check as per hash + Check if token is revoked
            String tokenHash = hashToken(refreshToken);
            String key = REFRESH_PREFIX + tokenHash;
            boolean exists = Boolean.TRUE.equals(redisTemplate.hasKey(key));
            log.debug("Refresh token exists in Redis: {}", exists);
            return exists;
        } catch (Exception e) {
            log.warn("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isRefreshTokenReused(String refreshToken) {
        String tokenHash = hashToken(refreshToken);
        String key = REFRESH_ROTATION_PREFIX + tokenHash;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        log.info("Start the revoke of the refresh token");
        String tokenHash = hashToken(refreshToken);
        String key = REFRESH_PREFIX + tokenHash;
        redisTemplate.delete(key);
        log.info("Refresh token revoked");
    }

    @Override
    public void revokeAllUserTokens(Long userId) {
        log.info("Revoking all tokens for user: userId={}", userId);
        String refreshPattern = USER_TOKENS_PREFIX + userId + ":*";
        var refreshKeys = redisTemplate.keys(refreshPattern);
        if (refreshKeys != null && !refreshKeys.isEmpty()) {
            // Для каждого refresh токена удаляем соответствующий ключ refresh:token
            refreshKeys.forEach(key -> {
                String tokenHash = key.substring(
                        USER_TOKENS_PREFIX.length() + userId.toString().length() + 1
                );
                String refreshKey = REFRESH_PREFIX + tokenHash;
                redisTemplate.delete(refreshKey);
                log.debug("Removed refresh key");
            });
            // Удаляем связи user:tokens:userId:*
            redisTemplate.delete(refreshKeys);
            log.info("All refresh tokens revoked for user: {}", userId);
        }
        String accessPattern = USER_ACCESS_PREFIX + userId + ":*";
        var accessKeys = redisTemplate.keys(accessPattern);
        if (accessKeys != null && !accessKeys.isEmpty()) {
            redisTemplate.delete(accessKeys);
            log.info("All access tokens revoked for user: {}", userId);
        }

        log.info("All tokens revoked for user: {}", userId);
    }

    @Override
    public void rotateRefreshToken(String oldRefreshToken) {
        log.info("Start of rotating refresh token");
        // Get user info from old token
        var oldPayload = jwtUtil.parseRefreshToken(oldRefreshToken);
        oldPayload.ifPresent(payload -> {
            // Получаем оставшееся время жизни старого refresh токена
            long remainingTtl = jwtUtil.getTokenRemainingTime(oldRefreshToken);
            String tokenHash = hashToken(oldRefreshToken);
            if (remainingTtl > 0) {
                // Сохраняем rotation ключ на ВЕСЬ оставшийся срок жизни токена
                String rotationKey = REFRESH_ROTATION_PREFIX + tokenHash;
                redisTemplate.opsForValue().set(
                        rotationKey,
                        payload.userId().toString(),
                        remainingTtl,
                        TimeUnit.MILLISECONDS);
                log.info("Rotation key set for {} ms for user: {}", remainingTtl, payload.userId());
            } else {
                log.warn("Old refresh token already expired, skipping rotation key");
            }
            // Revoke old token
            revokeRefreshToken(oldRefreshToken);
            log.info("Refresh token rotated for user: {}", payload.userId());
        });
    }

    @Override
    public boolean isAccessTokenBlacklisted(String accessToken) {
        log.info("Checking if token is blacklisted");
        String tokenHash = hashToken(accessToken);
        String key = BLACKLIST_PREFIX + tokenHash;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void blacklistAccessToken(String accessToken) {
        log.info("Adding token to blacklist");
        try {
            var payload = jwtUtil.parseAccessToken(accessToken);
            payload.ifPresent(p -> {
                long ttl = jwtUtil.getTokenRemainingTime(accessToken);
                String tokenHash = hashToken(accessToken);
                String key = BLACKLIST_PREFIX + tokenHash;
                redisTemplate.opsForValue().set(key, "blacklisted", ttl, TimeUnit.MILLISECONDS);
                String userKey = USER_ACCESS_PREFIX + p.userId() + ":" + tokenHash;
                redisTemplate.opsForValue().set(userKey, "blacklisted", ttl, TimeUnit.MILLISECONDS);
                log.debug("Access token blacklisted for user: {}", p.userId());
            });
        } catch (Exception e) {
            log.warn("Failed to blacklist token: {}", e.getMessage());
        }
    }

    @Override
    public void blacklistAllUserAccessTokens(Long userId) {
        log.info("Blacklisting all access tokens for user: userId={}", userId);
        String pattern = USER_ACCESS_PREFIX + userId + ":*";
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            // Добавляем все токены в blacklist
            keys.forEach(key -> {
                String tokenHash = key.substring(USER_ACCESS_PREFIX.length()
                        + userId.toString().length() + 1);
                String blacklistKey = BLACKLIST_PREFIX + tokenHash;
                long ttl = jwtUtil.getExpirationMs();
                redisTemplate.opsForValue().set(
                        blacklistKey,
                        "blacklisted",
                        ttl,
                        TimeUnit.MILLISECONDS
                );
                log.debug("Access token blacklisted for user: {}", userId);
            });
            log.info("All access tokens blacklisted for user: {}", userId);
        }
    }

    private void storeRefreshToken(String refreshToken, Long userId) {
        log.debug("Storing refresh token in Redis: userId={}", userId);
        // Store hash of refresh token
        String tokenHash = hashToken(refreshToken);
        String key = REFRESH_PREFIX + tokenHash;
        long ttl = jwtUtil.getRefreshExpirationMs();
        redisTemplate.opsForValue().set(key, userId.toString(), ttl, TimeUnit.MILLISECONDS);

        // Link token to user for easy revocation
        String userKey = USER_TOKENS_PREFIX + userId + ":" + tokenHash;
        redisTemplate.opsForValue().set(userKey, "active", ttl, TimeUnit.MILLISECONDS);
    }

    private void storeAccessToken(String accessToken, Long userId) {
        log.debug("Storing access token in Redis: userId={}", userId);
        String tokenHash = hashToken(accessToken);
        long ttl = jwtUtil.getExpirationMs();
        String userKey = USER_ACCESS_PREFIX + userId + ":" + tokenHash;
        redisTemplate.opsForValue().set(userKey, "active", ttl, TimeUnit.MILLISECONDS);
    }

    private String hashToken(String token) {
        return TokenHashUtil.hashToken(token);
    }
}
