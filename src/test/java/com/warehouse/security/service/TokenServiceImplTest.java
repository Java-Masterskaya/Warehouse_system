package com.warehouse.security.service;

import com.warehouse.exception.ActiveTokenLimitExceededException;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.TokenHashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(ACCESS_TOKEN);
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(REFRESH_TOKEN);
        when(jwtUtil.getTokenRemainingTime(REFRESH_TOKEN)).thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getTokenRemainingTime(ACCESS_TOKEN)).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        TokenPair result = tokenService.generateTokenPair(USERNAME, USER_ID, ROLES);

        assertThat(result).isEqualTo(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));
        String refreshHash = TokenHashUtil.hashToken(REFRESH_TOKEN);
        String accessHash = TokenHashUtil.hashToken(ACCESS_TOKEN);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "refresh:" + refreshHash,
                        "user:tokens:" + USER_ID + ":" + refreshHash,
                        "user:refresh:set:" + USER_ID,
                        "user:access:" + USER_ID + ":" + accessHash,
                        "user:access:set:" + USER_ID
                )),
                eq(USER_ID.toString()),
                eq(refreshHash),
                eq(accessHash),
                eq(Long.toString(REFRESH_EXPIRATION_MS)),
                eq(Long.toString(EXPIRATION_MS)),
                eq("100")
        );
    }

    @Test
    @DisplayName("Should reject a new login when the active token pair limit is reached")
    void generateTokenPairShouldRejectActiveTokenLimit() {
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(ACCESS_TOKEN);
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(REFRESH_TOKEN);
        when(jwtUtil.getTokenRemainingTime(REFRESH_TOKEN)).thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getTokenRemainingTime(ACCESS_TOKEN)).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(-3L);

        assertThatThrownBy(() -> tokenService.generateTokenPair(USERNAME, USER_ID, ROLES))
                .isInstanceOf(ActiveTokenLimitExceededException.class)
                .hasMessage("Maximum active token pairs per user has been reached");
    }

    @Test
    @DisplayName("Should validate refresh token when exists in Redis")
    void validateRefreshTokenWhenExistsShouldReturnTrue() {
        // Arrange
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(payload));
        when(redisTemplate.hasKey(argThat(key -> key.startsWith("refresh:")))).thenReturn(true);

        // Act
        boolean result = tokenService.validateRefreshToken(REFRESH_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(argThat((String key) -> key.startsWith("refresh:")));
    }

    @Test
    @DisplayName("Should not validate refresh token when not exists in Redis")
    void validateRefreshTokenWhenNotExistsShouldReturnFalse() {
        // Arrange
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(payload));
        when(redisTemplate.hasKey(argThat(key -> key.startsWith("refresh:")))).thenReturn(false);

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
        when(redisTemplate.hasKey(argThat(key -> key.startsWith("rotation:")))).thenReturn(true);

        // Act
        boolean result = tokenService.isRefreshTokenReused(REFRESH_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(argThat((String key) -> key.startsWith("rotation:")));
    }

    @Test
    @DisplayName("Should not detect reuse for new refresh token")
    void isRefreshTokenReusedForNewTokenShouldReturnFalse() {
        // Arrange
        when(redisTemplate.hasKey(argThat(key -> key.startsWith("rotation:")))).thenReturn(false);

        // Act
        boolean result = tokenService.isRefreshTokenReused(REFRESH_TOKEN);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should revoke refresh token from Redis")
    void revokeRefreshTokenShouldDeleteFromRedis() {
        // Act
        tokenService.revokeRefreshToken(REFRESH_TOKEN);

        // Assert
        verify(redisTemplate).delete(argThat((String k) -> k.startsWith("refresh:")));
    }

    @Test
    @DisplayName("Should revoke a valid token pair with one Redis script")
    void revokeTokenPairShouldExecuteAtomically() {
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(payload));
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(ACCESS_TOKEN)).thenReturn(EXPIRATION_MS);
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        tokenService.revokeTokenPair(REFRESH_TOKEN, ACCESS_TOKEN);

        String refreshHash = TokenHashUtil.hashToken(REFRESH_TOKEN);
        String accessHash = TokenHashUtil.hashToken(ACCESS_TOKEN);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "refresh:" + refreshHash,
                        "user:tokens:" + USER_ID + ":" + refreshHash,
                        "user:refresh:set:" + USER_ID,
                        "refresh:retry:owner:" + refreshHash,
                        "user:refresh:retry:set:" + USER_ID,
                        "rotation:" + refreshHash,
                        "user:access:set:" + USER_ID,
                        "blacklist:" + accessHash,
                        "user:access:" + USER_ID + ":" + accessHash
                )),
                eq(USER_ID.toString()),
                eq(refreshHash),
                eq(Long.toString(EXPIRATION_MS)),
                eq(accessHash),
                eq(Long.toString(EXPIRATION_MS))
        );
    }

    @Test
    @DisplayName("Should reject logout tokens that belong to different users")
    void revokeTokenPairShouldRejectDifferentUsers() {
        JwtUtil.JwtPayload refreshPayload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        JwtUtil.JwtPayload accessPayload = new JwtUtil.JwtPayload(2L, "other-user", ROLES);
        when(jwtUtil.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.of(refreshPayload));
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(accessPayload));

        assertThatThrownBy(() -> tokenService.revokeTokenPair(REFRESH_TOKEN, ACCESS_TOKEN))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Access and refresh tokens belong to different users");

        verify(redisTemplate, never()).execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("Should revoke all user tokens")
    void revokeAllUserTokensShouldDeleteAllTokens() {
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        tokenService.revokeAllUserTokens(USER_ID);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "user:refresh:set:" + USER_ID,
                        "user:access:set:" + USER_ID,
                        "user:refresh:retry:set:" + USER_ID
                )),
                eq(USER_ID.toString()),
                eq(Long.toString(EXPIRATION_MS))
        );
    }

    @Test
    @DisplayName("Should revoke all user tokens when no tokens exist")
    void revokeAllUserTokensWhenNoTokensShouldDoNothing() {
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(0L);

        tokenService.revokeAllUserTokens(USER_ID);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                eq(USER_ID.toString()),
                eq(Long.toString(EXPIRATION_MS))
        );
    }

    @Test
    @DisplayName("Should generate and commit refresh rotation with one Redis script")
    void rotateRefreshTokenShouldExecuteAtomicScript() {
        // Arrange
        String oldRefresh = "old-refresh-token";
        TokenPair newTokenPair = new TokenPair("new-access-token", "new-refresh-token");
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        long remainingTtl = 604800000L;
        long retryTtlSeconds = 60L;
        String oldTokenHash = TokenHashUtil.hashToken(oldRefresh);
        String newRefreshHash = TokenHashUtil.hashToken(newTokenPair.refreshToken());
        String newAccessHash = TokenHashUtil.hashToken(newTokenPair.accessToken());

        when(jwtUtil.parseRefreshToken(oldRefresh)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(oldRefresh)).thenReturn(remainingTtl);
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.accessToken());
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.refreshToken());
        when(jwtUtil.getTokenRemainingTime(newTokenPair.refreshToken()))
                .thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getTokenRemainingTime(newTokenPair.accessToken())).thenReturn(EXPIRATION_MS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(1L);
        ReflectionTestUtils.setField(tokenService, "refreshRetryTtlSeconds", retryTtlSeconds);

        // Act
        TokenPair result = tokenService.rotateRefreshToken(oldRefresh);

        // Assert
        assertThat(result).isEqualTo(newTokenPair);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keysCaptor.capture(),
                eq(USER_ID.toString()),
                eq(Long.toString(remainingTtl)),
                eq(newTokenPair.accessToken() + "|" + newTokenPair.refreshToken()),
                eq(Long.toString(TimeUnit.SECONDS.toMillis(retryTtlSeconds))),
                eq(oldTokenHash),
                eq(newRefreshHash),
                eq(newAccessHash),
                eq(Long.toString(REFRESH_EXPIRATION_MS)),
                eq(Long.toString(EXPIRATION_MS))
        );
        assertThat(keysCaptor.getValue()).containsExactly(
                "refresh:" + oldTokenHash,
                "rotation:" + oldTokenHash,
                "refresh:retry:" + oldTokenHash,
                "user:tokens:" + USER_ID + ":" + oldTokenHash,
                "user:refresh:set:" + USER_ID,
                "refresh:" + newRefreshHash,
                "user:tokens:" + USER_ID + ":" + newRefreshHash,
                "user:access:" + USER_ID + ":" + newAccessHash,
                "user:access:set:" + USER_ID,
                "user:refresh:retry:set:" + USER_ID,
                "refresh:retry:owner:" + newRefreshHash,
                "refresh:retry:owner:" + oldTokenHash
        );
    }

    @Test
    @DisplayName("Retry result TTL must not outlive the old refresh token")
    void rotateRefreshTokenShouldCapRetryTtlAtRemainingLifetime() {
        String oldRefresh = "near-expiry-refresh-token";
        TokenPair newTokenPair = new TokenPair("new-access-token", "new-refresh-token");
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        long remainingTtl = 500L;
        String oldTokenHash = TokenHashUtil.hashToken(oldRefresh);
        String newRefreshHash = TokenHashUtil.hashToken(newTokenPair.refreshToken());
        String newAccessHash = TokenHashUtil.hashToken(newTokenPair.accessToken());

        when(jwtUtil.parseRefreshToken(oldRefresh)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(oldRefresh)).thenReturn(remainingTtl);
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.accessToken());
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.refreshToken());
        when(jwtUtil.getTokenRemainingTime(newTokenPair.refreshToken()))
                .thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getTokenRemainingTime(newTokenPair.accessToken())).thenReturn(EXPIRATION_MS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(1L);
        ReflectionTestUtils.setField(tokenService, "refreshRetryTtlSeconds", 60L);

        tokenService.rotateRefreshToken(oldRefresh);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                eq(USER_ID.toString()),
                eq(Long.toString(remainingTtl)),
                eq(newTokenPair.accessToken() + "|" + newTokenPair.refreshToken()),
                eq(Long.toString(remainingTtl)),
                eq(oldTokenHash),
                eq(newRefreshHash),
                eq(newAccessHash),
                eq(Long.toString(REFRESH_EXPIRATION_MS)),
                eq(Long.toString(EXPIRATION_MS))
        );
    }

    @Test
    @DisplayName("Should fail closed when atomic refresh rotation is rejected")
    void rotateRefreshTokenWhenOldTokenIsMissingShouldThrow() {
        String oldRefresh = "missing-refresh-token";
        TokenPair newTokenPair = new TokenPair("new-access-token", "new-refresh-token");
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);

        when(jwtUtil.parseRefreshToken(oldRefresh)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(oldRefresh)).thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.generateToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.accessToken());
        when(jwtUtil.generateRefreshToken(USERNAME, USER_ID, ROLES)).thenReturn(newTokenPair.refreshToken());
        when(jwtUtil.getTokenRemainingTime(newTokenPair.refreshToken()))
                .thenReturn(REFRESH_EXPIRATION_MS);
        when(jwtUtil.getTokenRemainingTime(newTokenPair.accessToken())).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(tokenService, "refreshRetryTtlSeconds", 60L);

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(oldRefresh))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid or expired refresh token");
    }

    @Test
    @DisplayName("Should check if access token is blacklisted")
    void isAccessTokenBlacklistedShouldCheckRedis() {
        // Arrange
        when(redisTemplate.hasKey(argThat(key -> key.startsWith("blacklist:")))).thenReturn(true);

        // Act
        boolean result = tokenService.isAccessTokenBlacklisted(ACCESS_TOKEN);

        // Assert
        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(argThat((String key) -> key.startsWith("blacklist:")));
    }

    @Test
    @DisplayName("Should blacklist access token with correct TTL")
    void blacklistAccessTokenShouldSetWithCorrectTTL() {
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(USER_ID, USERNAME, ROLES);
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(ACCESS_TOKEN)).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        tokenService.blacklistAccessToken(ACCESS_TOKEN);

        String accessHash = TokenHashUtil.hashToken(ACCESS_TOKEN);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "blacklist:" + accessHash,
                        "user:access:" + USER_ID + ":" + accessHash,
                        "user:access:set:" + USER_ID
                )),
                eq(accessHash),
                eq(Long.toString(EXPIRATION_MS))
        );
    }

    @Test
    @DisplayName("Should blacklist all user access tokens")
    void blacklistAllUserAccessTokensShouldBlacklistAll() {
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        tokenService.blacklistAllUserAccessTokens(USER_ID);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("user:access:set:" + USER_ID)),
                eq(USER_ID.toString()),
                eq(Long.toString(EXPIRATION_MS))
        );
        verify(jwtUtil, times(1)).getExpirationMs();
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
    @DisplayName("Should propagate infrastructure failure during blacklist")
    void blacklistAccessTokenWhenExceptionShouldPropagate() {
        when(jwtUtil.parseAccessToken(ACCESS_TOKEN)).thenThrow(new RuntimeException("Test exception"));

        assertThatThrownBy(() -> tokenService.blacklistAccessToken(ACCESS_TOKEN))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception");
    }

    @Test
    @DisplayName("Should handle empty keys in blacklistAllUserAccessTokens")
    void blacklistAllUserAccessTokensWhenNoKeysShouldDoNothing() {
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(0L);

        tokenService.blacklistAllUserAccessTokens(USER_ID);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("user:access:set:" + USER_ID)),
                eq(USER_ID.toString()),
                eq(Long.toString(EXPIRATION_MS))
        );
    }
}
