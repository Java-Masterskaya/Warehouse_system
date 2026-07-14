package com.warehouse.security.service;

import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
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

    private static final String USER_REFRESH_SET_PREFIX = "user:refresh:set:";
    private static final String USER_ACCESS_SET_PREFIX = "user:access:set:";

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

        // 1. Отзываем refresh токены через SET-индекс
        String refreshSetKey = USER_REFRESH_SET_PREFIX + userId;
        Set<String> refreshTokenHashes = redisTemplate.opsForSet().members(refreshSetKey);
        if (refreshTokenHashes != null && !refreshTokenHashes.isEmpty()) {
            List<String> refreshKeysToDelete = refreshTokenHashes.stream()
                    .map(tokenHash -> REFRESH_PREFIX + tokenHash)
                    .toList();
            List<String> userKeysToDelete = refreshTokenHashes.stream()
                    .map(tokenHash -> USER_TOKENS_PREFIX + userId + ":" + tokenHash)
                    .toList();

            redisTemplate.delete(refreshKeysToDelete);
            redisTemplate.delete(userKeysToDelete);

            redisTemplate.delete(refreshSetKey);
            log.info("All refresh tokens revoked for user: {}", userId);
        }

        // 2. Отзываем access токены через SET-индекс
        String accessSetKey = USER_ACCESS_SET_PREFIX + userId;
        Set<String> accessTokenHashes = redisTemplate.opsForSet().members(accessSetKey);
        if (accessTokenHashes != null && !accessTokenHashes.isEmpty()) {
            List<String> accessKeysToDelete = accessTokenHashes.stream()
                    .map(tokenHash -> USER_ACCESS_PREFIX + userId + ":" + tokenHash)
                    .toList();

            redisTemplate.delete(accessKeysToDelete);

            // Добавляем в blacklist
            List<String> blacklistKeys = accessTokenHashes.stream()
                    .map(tokenHash -> BLACKLIST_PREFIX + tokenHash)
                    .toList();

            for (String blacklistKey : blacklistKeys) {
                redisTemplate.opsForValue().set(
                        blacklistKey,
                        "blacklisted",
                        jwtUtil.getExpirationMs(),
                        TimeUnit.MILLISECONDS
                );
            }
            // Удаляем SET-индекс
            redisTemplate.delete(accessSetKey);
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

        String accessSetKey = USER_ACCESS_SET_PREFIX + userId;
        Set<String> accessTokenHashes = redisTemplate.opsForSet().members(accessSetKey);

        if (accessTokenHashes != null && !accessTokenHashes.isEmpty()) {
            long ttl = jwtUtil.getExpirationMs();

            List<String> blacklistKeys = accessTokenHashes.stream()
                    .map(tokenHash -> BLACKLIST_PREFIX + tokenHash)
                    .toList();

            for (String blacklistKey : blacklistKeys) {
                redisTemplate.opsForValue().set(
                        blacklistKey,
                        "blacklisted",
                        ttl,
                        TimeUnit.MILLISECONDS
                );
            }
            log.info("All access tokens blacklisted for user: {}", userId);
        } else {
            log.debug("No access tokens to blacklist for user: {}", userId);
        }
    }

    private void storeRefreshToken(String refreshToken, Long userId) {
        log.debug("Storing refresh token in Redis: userId={}", userId);
        // Store hash of refresh token
        String tokenHash = hashToken(refreshToken);
        long ttl = jwtUtil.getRefreshExpirationMs();
        // Сохраняем сам токен
        String key = REFRESH_PREFIX + tokenHash;
        redisTemplate.opsForValue().set(key, userId.toString(), ttl, TimeUnit.MILLISECONDS);
        // Link token to user for easy revocation
        String userKey = USER_TOKENS_PREFIX + userId + ":" + tokenHash;
        redisTemplate.opsForValue().set(userKey, "active", ttl, TimeUnit.MILLISECONDS);

        // Добавляем в SET-индекс для быстрого отзыва
        String setKey = USER_REFRESH_SET_PREFIX + userId;
        redisTemplate.opsForSet().add(setKey, tokenHash);
        redisTemplate.expire(setKey, ttl, TimeUnit.MILLISECONDS);
    }

    private void storeAccessToken(String accessToken, Long userId) {
        log.debug("Storing access token in Redis: userId={}", userId);
        String tokenHash = hashToken(accessToken);
        long ttl = jwtUtil.getExpirationMs();
        String userKey = USER_ACCESS_PREFIX + userId + ":" + tokenHash;
        redisTemplate.opsForValue().set(userKey, "active", ttl, TimeUnit.MILLISECONDS);

        String setKey = USER_ACCESS_SET_PREFIX + userId;
        redisTemplate.opsForSet().add(setKey, tokenHash);
        redisTemplate.expire(setKey, ttl, TimeUnit.MILLISECONDS);
    }

    private String hashToken(String token) {
        return TokenHashUtil.hashToken(token);
    }
}
