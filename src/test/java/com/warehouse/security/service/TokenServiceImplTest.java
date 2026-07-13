package com.warehouse.security.service;

import com.warehouse.security.JwtUtil;
import com.warehouse.security.model.TokenPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService Unit Tests")
class TokenServiceImplTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenServiceImpl tokenService;

    private static final String USERNAME = "testuser";
    private static final Long USER_ID = 1L;
    private static final List<String> ROLES = List.of("ROLE_USER");
    private static final String ACCESS_TOKEN = "access-token-123";
    private static final String REFRESH_TOKEN = "refresh-token-456";
    private static final long EXPIRATION_MS = 86400000L;
    private static final long REFRESH_EXPIRATION_MS = 604800000L;

    @Test
    @DisplayName("Should generate token pair and store in Redis")
    void generateTokenPairShouldGenerateAndStoreTokens() {
        // Arrange
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(ACCESS_TOKEN);
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(REFRESH_TOKEN);
        when(jwtUtil.getRefreshExpirationMs()).thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Act
        TokenPair result = tokenService.generateTokenPair(USERNAME, USER_ID, ROLES);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);

        verify(valueOperations).set(
                eq("refresh:" + REFRESH_TOKEN),
                eq(USER_ID.toString()),
                eq(REFRESH_EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(valueOperations).set(
                eq("user:tokens:" + USER_ID + ":" + REFRESH_TOKEN),
                eq("active"),
                eq(REFRESH_EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(valueOperations).set(
                eq("user:access:" + USER_ID + ":" + ACCESS_TOKEN),
                eq("active"),
                eq(EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("Should validate refresh token when exists in Redis")
    void validateRefreshTokenWhenExistsShouldReturnTrue() {
        // Arrange
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(payload));
        when(redisTemplate.hasKey("refresh:" + REFRESH_TOKEN)).thenReturn(true);

        // Act
        boolean result = tokenService.validateRefreshToken(REFRESH_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey("refresh:" + REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Should not validate refresh token when not exists in Redis")
    void validateRefreshTokenWhenNotExistsShouldReturnFalse() {
        // Arrange
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(payload));
        when(redisTemplate.hasKey("refresh:" + REFRESH_TOKEN)).thenReturn(false);

        // Act
        boolean result = tokenService.validateRefreshToken(REFRESH_TOKEN);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should not validate refresh token when payload is invalid")
    void validateRefreshTokenWhenInvalidPayloadShouldReturnFalse() {
        // Arrange
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

        // Act
        boolean result = tokenService.validateRefreshToken(REFRESH_TOKEN);

        // Assert
        assertThat(result).isFalse();
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("Should detect refresh token reuse")
    void isRefreshTokenReusedShouldDetectReuse() {
        // Arrange
        when(redisTemplate.hasKey("rotation:" + REFRESH_TOKEN)).thenReturn(true);

        // Act
        boolean result = tokenService.isRefreshTokenReused(REFRESH_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey("rotation:" + REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Should not detect reuse for new refresh token")
    void isRefreshTokenReusedForNewTokenShouldReturnFalse() {
        // Arrange
        when(redisTemplate.hasKey("rotation:" + REFRESH_TOKEN)).thenReturn(false);

        // Act
        boolean result = tokenService.isRefreshTokenReused(REFRESH_TOKEN);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should revoke refresh token from Redis")
    void revokeRefreshTokenShouldDeleteFromRedis() {
        // Arrange
        String key = "refresh:" + REFRESH_TOKEN;

        // Act
        tokenService.revokeRefreshToken(REFRESH_TOKEN);

        // Assert
        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("Should revoke all user tokens")
    void revokeAllUserTokensShouldDeleteAllTokens() {
        // Arrange
        String refreshPattern = "user:tokens:" + USER_ID + ":*";
        String accessPattern = "user:access:" + USER_ID + ":*";

        @SuppressWarnings("unchecked")
        Set<String> refreshKeys = mock(Set.class);
        @SuppressWarnings("unchecked")
        Set<String> accessKeys = mock(Set.class);

        when(redisTemplate.keys(refreshPattern)).thenReturn(refreshKeys);
        when(redisTemplate.keys(accessPattern)).thenReturn(accessKeys);
        when(refreshKeys.isEmpty()).thenReturn(false);
        when(accessKeys.isEmpty()).thenReturn(false);

        // Act
        tokenService.revokeAllUserTokens(USER_ID);

        // Assert
        verify(redisTemplate).keys(refreshPattern);
        verify(redisTemplate).keys(accessPattern);
        verify(redisTemplate).delete(refreshKeys);
        verify(redisTemplate).delete(accessKeys);
    }

    @Test
    @DisplayName("Should revoke all user tokens when no tokens exist")
    void revokeAllUserTokensWhenNoTokensShouldDoNothing() {
        // Arrange
        String refreshPattern = "user:tokens:" + USER_ID + ":*";
        String accessPattern = "user:access:" + USER_ID + ":*";

        when(redisTemplate.keys(refreshPattern)).thenReturn(null);
        when(redisTemplate.keys(accessPattern)).thenReturn(null);

        // Act
        tokenService.revokeAllUserTokens(USER_ID);

        // Assert
        verify(redisTemplate).keys(refreshPattern);
        verify(redisTemplate).keys(accessPattern);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("Should rotate refresh token correctly")
    void rotateRefreshTokenShouldRotateCorrectly() {
        // Arrange
        String oldRefresh = "old-refresh-token";
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        long remainingTtl = 604800000L;

        when(jwtUtil.parseRefreshToken(oldRefresh)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(oldRefresh)).thenReturn(remainingTtl);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Act
        tokenService.rotateRefreshToken(oldRefresh);

        // Assert
        // 1. Старый refresh помечается как использованный
        verify(valueOperations).set(
                eq("rotation:" + oldRefresh),
                eq(USER_ID.toString()),
                eq(remainingTtl),
                eq(TimeUnit.MILLISECONDS)
        );

        // 2. Старый refresh удаляется
        verify(redisTemplate).delete("refresh:" + oldRefresh);

    }

    @Test
    @DisplayName("Should check if access token is blacklisted")
    void isAccessTokenBlacklistedShouldCheckRedis() {
        // Arrange
        when(redisTemplate.hasKey("blacklist:" + ACCESS_TOKEN)).thenReturn(true);

        // Act
        boolean result = tokenService.isAccessTokenBlacklisted(ACCESS_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey("blacklist:" + ACCESS_TOKEN);
    }

    @Test
    @DisplayName("Should blacklist access token with correct TTL")
    void blacklistAccessTokenShouldSetWithCorrectTTL() {
        // Arrange
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(ACCESS_TOKEN)).thenReturn(EXPIRATION_MS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Act
        tokenService.blacklistAccessToken(ACCESS_TOKEN);

        // Assert
        verify(valueOperations).set(
                eq("blacklist:" + ACCESS_TOKEN),
                eq("blacklisted"),
                eq(EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(valueOperations).set(
                eq("user:access:" + USER_ID + ":" + ACCESS_TOKEN),
                eq("blacklisted"),
                eq(EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("Should blacklist all user access tokens")
    void blacklistAllUserAccessTokensShouldBlacklistAll() {
        // Arrange
        String token1 = "access-token-1";
        String token2 = "access-token-2";
        String pattern = "user:access:" + USER_ID + ":*";

        Set<String> keys = new HashSet<>();
        keys.add("user:access:" + USER_ID + ":" + token1);
        keys.add("user:access:" + USER_ID + ":" + token2);

        when(redisTemplate.keys(pattern)).thenReturn(keys);

        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);

        when(jwtUtil.parseAccessToken(anyString())).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(anyString())).thenReturn(EXPIRATION_MS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Act
        tokenService.blacklistAllUserAccessTokens(USER_ID);

        // Assert
        verify(redisTemplate).keys(pattern);
        verify(jwtUtil, times(2)).parseAccessToken(anyString());
        verify(jwtUtil, times(2)).getTokenRemainingTime(anyString());
        verify(valueOperations, times(4)).set(
                anyString(),
                eq("blacklisted"),
                eq(EXPIRATION_MS),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("Should generate access token")
    void generateAccessTokenShouldCallJwtUtil() {
        // Arrange
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(ACCESS_TOKEN);

        // Act
        String result = tokenService.generateAccessToken(USERNAME, USER_ID, ROLES);

        // Assert
        assertThat(result).isEqualTo(ACCESS_TOKEN);
        verify(jwtUtil).generateToken(USERNAME, USER_ID, ROLES);
    }

    @Test
    @DisplayName("Should generate refresh token")
    void generateRefreshTokenShouldCallJwtUtil() {
        // Arrange
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(REFRESH_TOKEN);

        // Act
        String result = tokenService.generateRefreshToken(USERNAME, USER_ID, ROLES);

        // Assert
        assertThat(result).isEqualTo(REFRESH_TOKEN);
        verify(jwtUtil).generateRefreshToken(USERNAME, USER_ID, ROLES);
    }

    @Test
    @DisplayName("Should not blacklist invalid access token")
    void blacklistAccessTokenWhenInvalidTokenShouldNotSetBlacklist() {
        // Arrange
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenReturn(Optional.empty());

        // Act
        tokenService.blacklistAccessToken(ACCESS_TOKEN);

        // Assert
        verify(valueOperations, never()).set(
                anyString(),
                anyString(),
                anyLong(),
                any(TimeUnit.class)
        );
    }

    @Test
    @DisplayName("Should handle exception during blacklist")
    void blacklistAccessTokenWhenExceptionShouldNotThrow() {
        // Arrange
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenThrow(new RuntimeException("Test exception"));

        // Act & Assert - should not throw
        tokenService.blacklistAccessToken(ACCESS_TOKEN);

        // No assertion needed, just verify no exception
    }

    @Test
    @DisplayName("Should handle empty keys in blacklistAllUserAccessTokens")
    void blacklistAllUserAccessTokensWhenNoKeysShouldDoNothing() {
        // Arrange
        String pattern = "user:access:" + USER_ID + ":*";
        when(redisTemplate.keys(pattern)).thenReturn(null);

        // Act
        tokenService.blacklistAllUserAccessTokens(USER_ID);

        // Assert
        verify(redisTemplate).keys(pattern);
        verify(redisTemplate, never()).opsForValue();
    }
}