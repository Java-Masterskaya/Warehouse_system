package com.warehouse.security.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.exception.ActiveTokenLimitExceededException;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.TokenReuseException;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.service.TokenServiceImpl;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.util.TokenHashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@DisplayName("Atomic refresh rotation integration tests")
class RefreshRotationAtomicIntegrationTest extends AbstractIntegrationTest {

    private static final long ROTATION_TTL_MS = 120_000L;
    private static final long NEW_REFRESH_TTL_MS = 604_800_000L;
    private static final long NEW_ACCESS_TTL_MS = 86_400_000L;
    private static final long LONG_LIVED_REFRESH_TTL_MS = NEW_REFRESH_TTL_MS * 2;
    private static final long LONG_LIVED_ACCESS_TTL_MS = NEW_ACCESS_TTL_MS * 2;
    private static final long RETRY_TTL_SECONDS = 60L;
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10L;
    private static final List<String> ROLES = List.of("ROLE_USER");

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("Rotation must commit token storage, blacklist, and retry result atomically")
    void rotateRefreshTokenShouldCommitAllState() {
        String oldRefreshToken = "atomic-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        String oldAccessHash = TokenHashUtil.hashToken("old-access-" + UUID.randomUUID());
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair("new-access-" + UUID.randomUUID(), "new-refresh-" + UUID.randomUUID());
        JwtUtil jwtUtil = configuredJwtUtil(oldRefreshToken, userId, candidate);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);
        List<String> cleanupKeys = cleanupKeys(keys, userId, oldAccessHash, candidate);

        try {
            seedOldRefreshToken(keys, oldTokenHash, userId, oldAccessHash);
            seedAccessToken(userId, oldAccessHash, LONG_LIVED_ACCESS_TTL_MS);

            TokenPair result = tokenService.rotateRefreshToken(oldRefreshToken);

            assertThat(result).isEqualTo(candidate);
            assertThat(tokenService.validateRefreshToken(oldRefreshToken)).isFalse();
            assertThat(tokenService.isRefreshTokenReused(oldRefreshToken)).isTrue();
            assertThat(redisTemplate.opsForValue().get(keys.get(1))).isEqualTo(
                    userId + "|" + TokenHashUtil.hashToken(candidate.refreshToken())
                            + "|" + oldAccessHash
            );
            assertThat(tokenService.getRefreshRetryResult(oldRefreshToken)).contains(candidate);
            assertThat(redisTemplate.hasKey(keys.get(3))).isFalse();
            assertThat(redisTemplate.opsForSet().isMember(keys.get(4), oldTokenHash)).isFalse();
            assertThat(redisTemplate.opsForValue().get(keys.get(5))).isEqualTo(userId.toString());
            assertThat(redisTemplate.opsForValue().get(keys.get(6)))
                    .isEqualTo(TokenHashUtil.hashToken(candidate.accessToken()));
            assertThat(redisTemplate.opsForValue().get(keys.get(7))).isEqualTo("active");
            assertThat(redisTemplate.opsForSet().isMember(
                    keys.get(4),
                    TokenHashUtil.hashToken(candidate.refreshToken())
            )).isTrue();
            assertThat(redisTemplate.opsForSet().isMember(
                    keys.get(8),
                    TokenHashUtil.hashToken(candidate.accessToken())
            )).isTrue();
            assertThat(redisTemplate.opsForSet().members(keys.get(8)))
                    .containsExactly(TokenHashUtil.hashToken(candidate.accessToken()));
            assertThat(redisTemplate.hasKey(
                    "user:access:" + userId + ":" + oldAccessHash
            )).isFalse();
            assertThat(redisTemplate.opsForSet().isMember(keys.get(9), oldTokenHash)).isTrue();
            assertThat(redisTemplate.opsForValue().get(keys.get(10))).isEqualTo(oldTokenHash);
            assertThat(redisTemplate.hasKey("blacklist:" + oldAccessHash)).isTrue();
            assertThat(redisTemplate.getExpire(
                    "blacklist:" + oldAccessHash,
                    TimeUnit.MILLISECONDS
            )).isGreaterThan(NEW_ACCESS_TTL_MS);
            assertThat(redisTemplate.hasKey(
                    "blacklist:" + TokenHashUtil.hashToken(candidate.accessToken())
            )).isFalse();
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Rotating one token pair must preserve another active session")
    void rotateRefreshTokenShouldPreserveIndependentSession() {
        Long userId = randomUserId();
        TokenPair firstPair = new TokenPair(
                "first-access-" + UUID.randomUUID(),
                "first-refresh-" + UUID.randomUUID()
        );
        TokenPair secondPair = new TokenPair(
                "second-access-" + UUID.randomUUID(),
                "second-refresh-" + UUID.randomUUID()
        );
        TokenPair rotatedPair = new TokenPair(
                "rotated-access-" + UUID.randomUUID(),
                "rotated-refresh-" + UUID.randomUUID()
        );
        JwtUtil jwtUtil = tokenPairRotationJwtUtil(userId, firstPair, secondPair, rotatedPair);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        String firstRefreshHash = TokenHashUtil.hashToken(firstPair.refreshToken());
        String firstAccessHash = TokenHashUtil.hashToken(firstPair.accessToken());
        String secondRefreshHash = TokenHashUtil.hashToken(secondPair.refreshToken());
        String secondAccessHash = TokenHashUtil.hashToken(secondPair.accessToken());
        String rotatedRefreshHash = TokenHashUtil.hashToken(rotatedPair.refreshToken());
        String rotatedAccessHash = TokenHashUtil.hashToken(rotatedPair.accessToken());
        String refreshSetKey = "user:refresh:set:" + userId;
        String accessSetKey = "user:access:set:" + userId;
        List<String> cleanupKeys = new ArrayList<>(
                rotationKeys(firstRefreshHash, userId, rotatedPair)
        );
        cleanupKeys.addAll(candidateKeys(userId, firstPair));
        cleanupKeys.addAll(candidateKeys(userId, secondPair));
        cleanupKeys.add("blacklist:" + firstAccessHash);
        cleanupKeys.add("blacklist:" + secondAccessHash);
        cleanupKeys.add("blacklist:" + rotatedAccessHash);

        try {
            assertThat(tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isEqualTo(firstPair);
            assertThat(tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isEqualTo(secondPair);

            assertThat(tokenService.rotateRefreshToken(firstPair.refreshToken()))
                    .isEqualTo(rotatedPair);

            assertThat(redisTemplate.hasKey("refresh:" + firstRefreshHash)).isFalse();
            assertThat(redisTemplate.hasKey(
                    "user:tokens:" + userId + ":" + firstRefreshHash
            )).isFalse();
            assertThat(redisTemplate.hasKey(
                    "user:access:" + userId + ":" + firstAccessHash
            )).isFalse();
            assertThat(redisTemplate.hasKey("blacklist:" + firstAccessHash)).isTrue();

            assertThat(tokenService.validateRefreshToken(secondPair.refreshToken())).isTrue();
            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + secondRefreshHash
            )).isEqualTo(secondAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + secondAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + secondAccessHash)).isFalse();

            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + rotatedRefreshHash
            )).isEqualTo(rotatedAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + rotatedAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + rotatedAccessHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(refreshSetKey))
                    .containsExactlyInAnyOrder(secondRefreshHash, rotatedRefreshHash);
            assertThat(redisTemplate.opsForSet().members(accessSetKey))
                    .containsExactlyInAnyOrder(secondAccessHash, rotatedAccessHash);

            tokenService.revokeTokenPair(
                    secondPair.refreshToken(),
                    secondPair.accessToken()
            );
            tokenService.revokeTokenPair(
                    secondPair.refreshToken(),
                    rotatedPair.accessToken()
            );

            assertThat(redisTemplate.hasKey("refresh:" + rotatedRefreshHash)).isTrue();
            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + rotatedRefreshHash
            )).isEqualTo(rotatedAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + rotatedAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + rotatedAccessHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(refreshSetKey))
                    .containsExactly(rotatedRefreshHash);
            assertThat(redisTemplate.opsForSet().members(accessSetKey))
                    .containsExactly(rotatedAccessHash);
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Legacy rotation must upgrade the pair without revoking existing access tokens")
    void rotateLegacyRefreshTokenShouldPreserveExistingSessionsAndUpgradeMapping() {
        Long userId = randomUserId();
        String firstRefresh = "legacy-first-refresh-" + UUID.randomUUID();
        String secondRefresh = "legacy-second-refresh-" + UUID.randomUUID();
        String firstRefreshHash = TokenHashUtil.hashToken(firstRefresh);
        String secondRefreshHash = TokenHashUtil.hashToken(secondRefresh);
        String firstAccessHash = TokenHashUtil.hashToken(
                "legacy-first-access-" + UUID.randomUUID()
        );
        String secondAccessHash = TokenHashUtil.hashToken(
                "legacy-second-access-" + UUID.randomUUID()
        );
        TokenPair rotatedPair = new TokenPair(
                "legacy-rotated-access-" + UUID.randomUUID(),
                "legacy-rotated-refresh-" + UUID.randomUUID()
        );
        String rotatedRefreshHash = TokenHashUtil.hashToken(rotatedPair.refreshToken());
        String rotatedAccessHash = TokenHashUtil.hashToken(rotatedPair.accessToken());
        JwtUtil jwtUtil = configuredJwtUtil(firstRefresh, userId, rotatedPair);
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(userId, "cluster-user", ROLES);
        when(jwtUtil.parseRefreshToken(secondRefresh)).thenReturn(Optional.of(payload));
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        List<String> keys = rotationKeys(firstRefreshHash, userId, rotatedPair);
        String secondRefreshKey = "refresh:" + secondRefreshHash;
        String secondMappingKey = "user:tokens:" + userId + ":" + secondRefreshHash;
        String firstAccessKey = "user:access:" + userId + ":" + firstAccessHash;
        String secondAccessKey = "user:access:" + userId + ":" + secondAccessHash;
        List<String> cleanupKeys = new ArrayList<>(keys);
        cleanupKeys.add(secondRefreshKey);
        cleanupKeys.add(secondMappingKey);
        cleanupKeys.add(firstAccessKey);
        cleanupKeys.add(secondAccessKey);
        cleanupKeys.add("blacklist:" + firstAccessHash);
        cleanupKeys.add("blacklist:" + secondAccessHash);
        cleanupKeys.add("blacklist:" + rotatedAccessHash);

        try {
            redisTemplate.opsForValue().set(
                    keys.get(0),
                    userId.toString(),
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForValue().set(
                    keys.get(3),
                    "active",
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForValue().set(
                    secondRefreshKey,
                    userId.toString(),
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForValue().set(
                    secondMappingKey,
                    "active",
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForSet().add(keys.get(4), firstRefreshHash, secondRefreshHash);
            redisTemplate.expire(keys.get(4), ROTATION_TTL_MS, TimeUnit.MILLISECONDS);
            seedAccessToken(userId, firstAccessHash);
            seedAccessToken(userId, secondAccessHash);

            assertThat(tokenService.rotateRefreshToken(firstRefresh)).isEqualTo(rotatedPair);

            assertThat(redisTemplate.hasKey(keys.get(0))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(3))).isFalse();
            assertThat(tokenService.validateRefreshToken(secondRefresh)).isTrue();
            assertThat(redisTemplate.opsForValue().get(secondMappingKey)).isEqualTo("active");
            assertThat(redisTemplate.opsForValue().get(firstAccessKey)).isEqualTo("active");
            assertThat(redisTemplate.opsForValue().get(secondAccessKey)).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + firstAccessHash)).isFalse();
            assertThat(redisTemplate.hasKey("blacklist:" + secondAccessHash)).isFalse();
            assertThat(redisTemplate.opsForValue().get(keys.get(6)))
                    .isEqualTo(rotatedAccessHash);
            assertThat(redisTemplate.opsForSet().members(keys.get(4)))
                    .containsExactlyInAnyOrder(secondRefreshHash, rotatedRefreshHash);
            assertThat(redisTemplate.opsForSet().members(keys.get(8)))
                    .containsExactlyInAnyOrder(
                            firstAccessHash,
                            secondAccessHash,
                            rotatedAccessHash
                );
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Missing old token must not store the candidate or mutate user indexes")
    void rotateRefreshTokenWhenOldTokenIsMissingShouldNotPublishCandidate() {
        String oldRefreshToken = "missing-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair("new-access-" + UUID.randomUUID(), "new-refresh-" + UUID.randomUUID());
        TokenServiceImpl tokenService = createTokenService(configuredJwtUtil(oldRefreshToken, userId, candidate));
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);

        try {
            redisTemplate.opsForValue().set(
                    keys.get(3),
                    "active",
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForSet().add(keys.get(4), oldTokenHash);
            redisTemplate.expire(keys.get(4), ROTATION_TTL_MS, TimeUnit.MILLISECONDS);

            assertThatThrownBy(() -> tokenService.rotateRefreshToken(oldRefreshToken))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessage("Invalid or expired refresh token");

            assertThat(redisTemplate.hasKey(keys.get(1))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(2))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(3))).isTrue();
            assertThat(redisTemplate.opsForSet().isMember(keys.get(4), oldTokenHash)).isTrue();
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(6))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(7))).isFalse();
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Reuse marker without retry result must revoke the active user token chain")
    void reusedRefreshTokenShouldRevokeActiveTokenChain() {
        String oldRefreshToken = "reused-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        String activeAccessHash = TokenHashUtil.hashToken("active-access-" + UUID.randomUUID());
        String activeRefreshHash = TokenHashUtil.hashToken("active-refresh-" + UUID.randomUUID());
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair("new-access-" + UUID.randomUUID(), "new-refresh-" + UUID.randomUUID());
        TokenServiceImpl tokenService = createTokenService(configuredJwtUtil(oldRefreshToken, userId, candidate));
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);
        String activeAccessKey = "user:access:" + userId + ":" + activeAccessHash;
        String activeRefreshKey = "refresh:" + activeRefreshHash;
        String activeRefreshUserKey = "user:tokens:" + userId + ":" + activeRefreshHash;
        List<String> cleanupKeys = new ArrayList<>(keys);
        cleanupKeys.add(activeAccessKey);
        cleanupKeys.add("blacklist:" + activeAccessHash);
        cleanupKeys.add(activeRefreshKey);
        cleanupKeys.add(activeRefreshUserKey);

        try {
            redisTemplate.opsForValue().set(
                    keys.get(1),
                    userId.toString(),
                    ROTATION_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            seedAccessToken(userId, activeAccessHash);
            redisTemplate.opsForValue().set(
                    activeRefreshKey,
                    userId.toString(),
                    NEW_REFRESH_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForValue().set(
                    activeRefreshUserKey,
                    activeAccessHash,
                    NEW_REFRESH_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForSet().add(keys.get(4), activeRefreshHash);
            redisTemplate.expire(keys.get(4), NEW_REFRESH_TTL_MS, TimeUnit.MILLISECONDS);

            assertThatThrownBy(() -> tokenService.rotateRefreshToken(oldRefreshToken))
                    .isInstanceOf(TokenReuseException.class)
                    .hasMessage("Token reuse detected - all tokens revoked");

            assertThat(redisTemplate.hasKey("blacklist:" + activeAccessHash)).isTrue();
            assertThat(redisTemplate.hasKey(activeAccessKey)).isFalse();
            assertThat(redisTemplate.hasKey(activeRefreshKey)).isFalse();
            assertThat(redisTemplate.hasKey(activeRefreshUserKey)).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(4))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(7))).isFalse();
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Logout must remove the rotation retry result linked to the active refresh")
    void revokeRefreshTokenShouldRemoveLinkedRetryResult() {
        String oldRefreshToken = "logout-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair("new-access-" + UUID.randomUUID(), "new-refresh-" + UUID.randomUUID());
        TokenServiceImpl tokenService = createTokenService(configuredJwtUtil(oldRefreshToken, userId, candidate));
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);

        try {
            seedOldRefreshToken(keys, oldTokenHash, userId);
            tokenService.rotateRefreshToken(oldRefreshToken);
            assertThat(redisTemplate.hasKey(keys.get(2))).isTrue();
            assertThat(redisTemplate.hasKey(keys.get(10))).isTrue();

            tokenService.revokeRefreshToken(candidate.refreshToken());

            assertThat(redisTemplate.hasKey(keys.get(2))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(6))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(10))).isFalse();
            assertThat(redisTemplate.opsForSet().isMember(keys.get(9), oldTokenHash)).isFalse();
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Logout of a consumed predecessor must revoke its rotation winner")
    void revokeConsumedPredecessorShouldRevokeRotationWinner() {
        String oldRefreshToken = "rotated-logout-r0-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        String independentRefreshHash = TokenHashUtil.hashToken(
                "independent-refresh-" + UUID.randomUUID()
        );
        String independentAccessHash = TokenHashUtil.hashToken(
                "independent-access-" + UUID.randomUUID()
        );
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair(
                "rotated-logout-a1-" + UUID.randomUUID(),
                "rotated-logout-r1-" + UUID.randomUUID()
        );
        TokenServiceImpl tokenService = createTokenService(
                configuredJwtUtil(oldRefreshToken, userId, candidate)
        );
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);
        String candidateBlacklist = "blacklist:" + TokenHashUtil.hashToken(candidate.accessToken());
        String independentRefreshKey = "refresh:" + independentRefreshHash;
        String independentMappingKey = "user:tokens:" + userId + ":" + independentRefreshHash;
        String independentAccessKey = "user:access:" + userId + ":" + independentAccessHash;
        List<String> cleanupKeys = new ArrayList<>(keys);
        cleanupKeys.add(candidateBlacklist);
        cleanupKeys.add(independentRefreshKey);
        cleanupKeys.add(independentMappingKey);
        cleanupKeys.add(independentAccessKey);
        cleanupKeys.add("blacklist:" + independentAccessHash);

        try {
            seedOldRefreshToken(keys, oldTokenHash, userId);
            redisTemplate.opsForValue().set(
                    independentRefreshKey,
                    userId.toString(),
                    NEW_REFRESH_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForValue().set(
                    independentMappingKey,
                    independentAccessHash,
                    NEW_REFRESH_TTL_MS,
                    TimeUnit.MILLISECONDS
            );
            redisTemplate.opsForSet().add(keys.get(4), independentRefreshHash);
            seedAccessToken(userId, independentAccessHash);
            assertThat(tokenService.rotateRefreshToken(oldRefreshToken)).isEqualTo(candidate);

            tokenService.revokeRefreshToken(oldRefreshToken);

            assertThat(redisTemplate.hasKey(keys.get(2))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(6))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(7))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(9))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(10))).isFalse();
            assertThat(redisTemplate.hasKey(candidateBlacklist)).isTrue();
            assertThat(redisTemplate.opsForValue().get(independentRefreshKey))
                    .isEqualTo(userId.toString());
            assertThat(redisTemplate.opsForValue().get(independentMappingKey))
                    .isEqualTo(independentAccessHash);
            assertThat(redisTemplate.opsForValue().get(independentAccessKey)).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + independentAccessHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(keys.get(4)))
                    .containsExactly(independentRefreshHash);
            assertThat(redisTemplate.opsForSet().members(keys.get(8)))
                    .containsExactly(independentAccessHash);
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Logout must reject tokens from different sessions before and after rotation")
    void revokeTokenPairShouldRejectDifferentSessionPair() {
        Long userId = randomUserId();
        TokenPair firstPair = new TokenPair(
                "logout-first-access-" + UUID.randomUUID(),
                "logout-first-refresh-" + UUID.randomUUID()
        );
        TokenPair secondPair = new TokenPair(
                "logout-second-access-" + UUID.randomUUID(),
                "logout-second-refresh-" + UUID.randomUUID()
        );
        TokenPair unusedPair = new TokenPair(
                "logout-unused-access-" + UUID.randomUUID(),
                "logout-unused-refresh-" + UUID.randomUUID()
        );
        JwtUtil jwtUtil = tokenPairRotationJwtUtil(userId, firstPair, secondPair, unusedPair);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        String firstRefreshHash = TokenHashUtil.hashToken(firstPair.refreshToken());
        String firstAccessHash = TokenHashUtil.hashToken(firstPair.accessToken());
        String secondRefreshHash = TokenHashUtil.hashToken(secondPair.refreshToken());
        String secondAccessHash = TokenHashUtil.hashToken(secondPair.accessToken());
        String rotatedRefreshHash = TokenHashUtil.hashToken(unusedPair.refreshToken());
        String rotatedAccessHash = TokenHashUtil.hashToken(unusedPair.accessToken());
        String refreshSetKey = "user:refresh:set:" + userId;
        String accessSetKey = "user:access:set:" + userId;
        List<String> cleanupKeys = new ArrayList<>(candidateKeys(userId, firstPair));
        cleanupKeys.addAll(candidateKeys(userId, secondPair));
        cleanupKeys.addAll(rotationKeys(firstRefreshHash, userId, unusedPair));
        cleanupKeys.add("blacklist:" + firstAccessHash);
        cleanupKeys.add("blacklist:" + secondAccessHash);
        cleanupKeys.add("blacklist:" + rotatedAccessHash);

        try {
            assertThat(tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isEqualTo(firstPair);
            assertThat(tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isEqualTo(secondPair);

            assertThatThrownBy(() -> tokenService.revokeTokenPair(
                    firstPair.refreshToken(),
                    secondPair.accessToken()
            ))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessage("Access and refresh tokens do not form a token pair");

            assertThat(redisTemplate.hasKey("refresh:" + firstRefreshHash)).isTrue();
            assertThat(redisTemplate.hasKey("refresh:" + secondRefreshHash)).isTrue();
            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + firstRefreshHash
            )).isEqualTo(firstAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + secondRefreshHash
            )).isEqualTo(secondAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + firstAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + secondAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + firstAccessHash)).isFalse();
            assertThat(redisTemplate.hasKey("blacklist:" + secondAccessHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(refreshSetKey))
                    .containsExactlyInAnyOrder(firstRefreshHash, secondRefreshHash);
            assertThat(redisTemplate.opsForSet().members(accessSetKey))
                    .containsExactlyInAnyOrder(firstAccessHash, secondAccessHash);

            assertThat(tokenService.rotateRefreshToken(firstPair.refreshToken()))
                    .isEqualTo(unusedPair);
            assertThatThrownBy(() -> tokenService.revokeTokenPair(
                    firstPair.refreshToken(),
                    secondPair.accessToken()
            ))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessage("Access and refresh tokens do not form a token pair");

            assertThat(redisTemplate.hasKey("refresh:" + rotatedRefreshHash)).isTrue();
            assertThat(redisTemplate.opsForValue().get(
                    "user:tokens:" + userId + ":" + rotatedRefreshHash
            )).isEqualTo(rotatedAccessHash);
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + rotatedAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("refresh:" + secondRefreshHash)).isTrue();
            assertThat(redisTemplate.opsForValue().get(
                    "user:access:" + userId + ":" + secondAccessHash
            )).isEqualTo("active");
            assertThat(redisTemplate.hasKey("blacklist:" + secondAccessHash)).isFalse();
            assertThat(redisTemplate.hasKey("blacklist:" + rotatedAccessHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(refreshSetKey))
                    .containsExactlyInAnyOrder(secondRefreshHash, rotatedRefreshHash);
            assertThat(redisTemplate.opsForSet().members(accessSetKey))
                    .containsExactlyInAnyOrder(secondAccessHash, rotatedAccessHash);
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Logout before rotation must prevent publication of a new token pair")
    void revokeBeforeRotationShouldPreventCandidatePublication() {
        String oldRefreshToken = "logout-first-r0-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair(
                "logout-first-a1-" + UUID.randomUUID(),
                "logout-first-r1-" + UUID.randomUUID()
        );
        TokenServiceImpl tokenService = createTokenService(
                configuredJwtUtil(oldRefreshToken, userId, candidate)
        );
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);

        try {
            seedOldRefreshToken(keys, oldTokenHash, userId);

            tokenService.revokeRefreshToken(oldRefreshToken);

            assertThatThrownBy(() -> tokenService.rotateRefreshToken(oldRefreshToken))
                    .isInstanceOf(InvalidTokenException.class);
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(7))).isFalse();
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Multi-hop rotation must retire the predecessor retry result atomically")
    void multiHopRotationShouldRetirePredecessorRetry() {
        String firstRefresh = "multi-hop-r0-" + UUID.randomUUID();
        Long userId = randomUserId();
        TokenPair firstCandidate = new TokenPair(
                "multi-hop-a1-" + UUID.randomUUID(),
                "multi-hop-r1-" + UUID.randomUUID()
        );
        TokenPair secondCandidate = new TokenPair(
                "multi-hop-a2-" + UUID.randomUUID(),
                "multi-hop-r2-" + UUID.randomUUID()
        );
        JwtUtil jwtUtil = mock(JwtUtil.class);
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(userId, "cluster-user", ROLES);
        when(jwtUtil.parseRefreshToken(firstRefresh)).thenReturn(Optional.of(payload));
        when(jwtUtil.parseRefreshToken(firstCandidate.refreshToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(firstRefresh)).thenReturn(ROTATION_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(firstCandidate.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(firstCandidate.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondCandidate.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondCandidate.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.generateToken("cluster-user", userId, ROLES))
                .thenReturn(firstCandidate.accessToken(), secondCandidate.accessToken());
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES))
                .thenReturn(firstCandidate.refreshToken(), secondCandidate.refreshToken());
        when(jwtUtil.getExpirationMs()).thenReturn(NEW_ACCESS_TTL_MS);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        String firstHash = TokenHashUtil.hashToken(firstRefresh);
        String intermediateHash = TokenHashUtil.hashToken(firstCandidate.refreshToken());
        List<String> firstKeys = rotationKeys(firstHash, userId, firstCandidate);
        List<String> secondKeys = rotationKeys(intermediateHash, userId, secondCandidate);
        String secondCandidateBlacklist = "blacklist:"
                + TokenHashUtil.hashToken(secondCandidate.accessToken());
        List<String> cleanupKeys = new ArrayList<>(firstKeys);
        cleanupKeys.addAll(secondKeys);
        cleanupKeys.add("blacklist:" + TokenHashUtil.hashToken(firstCandidate.accessToken()));
        cleanupKeys.add(secondCandidateBlacklist);

        try {
            seedOldRefreshToken(firstKeys, firstHash, userId);

            assertThat(tokenService.rotateRefreshToken(firstRefresh)).isEqualTo(firstCandidate);
            assertThat(tokenService.getRefreshRetryResult(firstRefresh)).contains(firstCandidate);

            assertThat(tokenService.rotateRefreshToken(firstCandidate.refreshToken()))
                    .isEqualTo(secondCandidate);

            assertThat(tokenService.getRefreshRetryResult(firstRefresh)).isEmpty();
            assertThat(tokenService.getRefreshRetryResult(firstCandidate.refreshToken()))
                    .contains(secondCandidate);
            assertThat(redisTemplate.hasKey("refresh:retry:owner:" + intermediateHash)).isFalse();
            assertThat(redisTemplate.opsForSet().members(
                    "user:refresh:retry:set:" + userId
            )).containsExactly(intermediateHash);

            tokenService.revokeRefreshToken(firstRefresh);

            assertThat(redisTemplate.hasKey(secondKeys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(secondKeys.get(6))).isFalse();
            assertThat(redisTemplate.hasKey(secondKeys.get(7))).isFalse();
            assertThat(redisTemplate.hasKey(secondKeys.get(9))).isFalse();
            assertThat(redisTemplate.hasKey(secondKeys.get(10))).isFalse();
            assertThat(redisTemplate.hasKey(secondCandidateBlacklist)).isTrue();
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("User-wide revoke must remove winner tokens and retry state in one Redis script")
    void revokeAllUserTokensShouldRemoveRotatedState() {
        String oldRefreshToken = "revoke-all-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        Long userId = randomUserId();
        TokenPair candidate = new TokenPair("new-access-" + UUID.randomUUID(), "new-refresh-" + UUID.randomUUID());
        TokenServiceImpl tokenService = createTokenService(configuredJwtUtil(oldRefreshToken, userId, candidate));
        List<String> keys = rotationKeys(oldTokenHash, userId, candidate);
        String candidateBlacklist = "blacklist:" + TokenHashUtil.hashToken(candidate.accessToken());
        List<String> cleanupKeys = new ArrayList<>(keys);
        cleanupKeys.add(candidateBlacklist);

        try {
            seedOldRefreshToken(keys, oldTokenHash, userId);
            tokenService.rotateRefreshToken(oldRefreshToken);

            tokenService.revokeAllUserTokens(userId);

            assertThat(redisTemplate.hasKey(keys.get(2))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(5))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(6))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(7))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(8))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(9))).isFalse();
            assertThat(redisTemplate.hasKey(keys.get(10))).isFalse();
            assertThat(redisTemplate.hasKey(candidateBlacklist)).isTrue();
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Token index TTL must not shrink when refreshed configuration uses shorter TTL")
    void tokenIndexTtlShouldPreserveLongerExistingTokens() {
        Long userId = randomUserId();
        TokenPair firstPair = new TokenPair("long-access-" + UUID.randomUUID(), "long-refresh-" + UUID.randomUUID());
        TokenPair secondPair = new TokenPair("short-access-" + UUID.randomUUID(), "short-refresh-" + UUID.randomUUID());
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken("cluster-user", userId, ROLES))
                .thenReturn(firstPair.accessToken(), secondPair.accessToken());
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES))
                .thenReturn(firstPair.refreshToken(), secondPair.refreshToken());
        when(jwtUtil.getTokenRemainingTime(firstPair.refreshToken()))
                .thenReturn(LONG_LIVED_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(firstPair.accessToken()))
                .thenReturn(LONG_LIVED_ACCESS_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        String refreshSetKey = "user:refresh:set:" + userId;
        String accessSetKey = "user:access:set:" + userId;
        List<String> cleanupKeys = new ArrayList<>();
        cleanupKeys.add(refreshSetKey);
        cleanupKeys.add(accessSetKey);
        cleanupKeys.addAll(candidateKeys(userId, firstPair));
        cleanupKeys.addAll(candidateKeys(userId, secondPair));

        try {
            tokenService.generateTokenPair("cluster-user", userId, ROLES);
            tokenService.generateTokenPair("cluster-user", userId, ROLES);

            assertThat(redisTemplate.getExpire(refreshSetKey, TimeUnit.MILLISECONDS))
                    .isGreaterThan(NEW_REFRESH_TTL_MS);
            assertThat(redisTemplate.getExpire(accessSetKey, TimeUnit.MILLISECONDS))
                    .isGreaterThan(NEW_ACCESS_TTL_MS);
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Login token storage must enforce the per-user active pair limit atomically")
    void generateTokenPairShouldEnforceActivePairLimit() {
        Long userId = randomUserId();
        TokenPair firstPair = new TokenPair(
                "limited-access-" + UUID.randomUUID(),
                "limited-refresh-" + UUID.randomUUID()
        );
        TokenPair rejectedPair = new TokenPair(
                "rejected-access-" + UUID.randomUUID(),
                "rejected-refresh-" + UUID.randomUUID()
        );
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken("cluster-user", userId, ROLES))
                .thenReturn(firstPair.accessToken(), rejectedPair.accessToken());
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES))
                .thenReturn(firstPair.refreshToken(), rejectedPair.refreshToken());
        when(jwtUtil.getTokenRemainingTime(firstPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(firstPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(rejectedPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(rejectedPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        ReflectionTestUtils.setField(tokenService, "maxActiveTokenPairsPerUser", 1L);
        List<String> cleanupKeys = new ArrayList<>();
        cleanupKeys.add("user:refresh:set:" + userId);
        cleanupKeys.add("user:access:set:" + userId);
        cleanupKeys.addAll(candidateKeys(userId, firstPair));
        cleanupKeys.addAll(candidateKeys(userId, rejectedPair));

        try {
            assertThat(tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isEqualTo(firstPair);

            assertThatThrownBy(() -> tokenService.generateTokenPair("cluster-user", userId, ROLES))
                    .isInstanceOf(ActiveTokenLimitExceededException.class);

            assertThat(redisTemplate.hasKey(
                    "refresh:" + TokenHashUtil.hashToken(rejectedPair.refreshToken())
            )).isFalse();
            assertThat(redisTemplate.hasKey(
                    "user:access:" + userId + ":"
                            + TokenHashUtil.hashToken(rejectedPair.accessToken())
            )).isFalse();
        } finally {
            redisTemplate.delete(cleanupKeys);
        }
    }

    @Test
    @DisplayName("Concurrent CAS attempts must expose one winner without orphan token state")
    void concurrentRotationsShouldReturnWinnerWithoutOrphans() throws Exception {
        String oldRefreshToken = "concurrent-refresh-" + UUID.randomUUID();
        String oldTokenHash = TokenHashUtil.hashToken(oldRefreshToken);
        String oldAccessHash = TokenHashUtil.hashToken("old-access-" + UUID.randomUUID());
        Long userId = randomUserId();
        ConcurrentLinkedQueue<TokenPair> generatedCandidates = new ConcurrentLinkedQueue<>();
        ThreadLocal<TokenPair> threadCandidate = ThreadLocal.withInitial(() -> {
            TokenPair candidate = new TokenPair(
                    "candidate-access-" + UUID.randomUUID(),
                    "candidate-refresh-" + UUID.randomUUID()
            );
            generatedCandidates.add(candidate);
            return candidate;
        });
        CountDownLatch bothCandidatesReady = new CountDownLatch(2);
        JwtUtil jwtUtil = concurrentJwtUtil(
                oldRefreshToken,
                userId,
                threadCandidate,
                bothCandidatesReady
        );
        TokenServiceImpl tokenService = createTokenService(jwtUtil);
        List<String> oldKeys = oldRotationKeys(oldTokenHash, userId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            seedOldRefreshToken(oldKeys, oldTokenHash, userId, oldAccessHash);
            seedAccessToken(userId, oldAccessHash);

            Future<TokenPair> first = executor.submit(() -> tokenService.rotateRefreshToken(oldRefreshToken));
            Future<TokenPair> second = executor.submit(() -> tokenService.rotateRefreshToken(oldRefreshToken));
            TokenPair firstResult = first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            TokenPair secondResult = second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(generatedCandidates).hasSize(2);
            assertThat(generatedCandidates).contains(firstResult);
            assertThat(redisTemplate.hasKey("blacklist:" + oldAccessHash)).isTrue();
            assertThat(redisTemplate.hasKey(
                    "blacklist:" + TokenHashUtil.hashToken(firstResult.accessToken())
            )).isFalse();

            for (TokenPair candidate : generatedCandidates) {
                String refreshKey = "refresh:" + TokenHashUtil.hashToken(candidate.refreshToken());
                String accessKey = "user:access:" + userId + ":"
                        + TokenHashUtil.hashToken(candidate.accessToken());
                if (candidate.equals(firstResult)) {
                    assertThat(redisTemplate.hasKey(refreshKey)).isTrue();
                    assertThat(redisTemplate.hasKey(accessKey)).isTrue();
                } else {
                    assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
                    assertThat(redisTemplate.hasKey(accessKey)).isFalse();
                }
            }
        } finally {
            executor.shutdownNow();
            List<String> cleanupKeys = new ArrayList<>(oldKeys);
            cleanupKeys.add("user:access:" + userId + ":" + oldAccessHash);
            cleanupKeys.add("user:access:set:" + userId);
            cleanupKeys.add("user:refresh:retry:set:" + userId);
            cleanupKeys.add("blacklist:" + oldAccessHash);
            for (TokenPair candidate : generatedCandidates) {
                cleanupKeys.addAll(candidateKeys(userId, candidate));
            }
            redisTemplate.delete(cleanupKeys);
        }
    }

    private JwtUtil configuredJwtUtil(String oldRefreshToken, Long userId, TokenPair candidate) {
        JwtUtil jwtUtil = baseJwtUtil(oldRefreshToken, userId);
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(userId, "cluster-user", ROLES);
        when(jwtUtil.parseRefreshToken(candidate.refreshToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.generateToken("cluster-user", userId, ROLES)).thenReturn(candidate.accessToken());
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES)).thenReturn(candidate.refreshToken());
        when(jwtUtil.getTokenRemainingTime(candidate.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(candidate.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getExpirationMs()).thenReturn(NEW_ACCESS_TTL_MS);
        return jwtUtil;
    }

    private JwtUtil tokenPairRotationJwtUtil(
            Long userId,
            TokenPair firstPair,
            TokenPair secondPair,
            TokenPair rotatedPair
    ) {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(userId, "cluster-user", ROLES);
        when(jwtUtil.generateToken("cluster-user", userId, ROLES)).thenReturn(
                firstPair.accessToken(),
                secondPair.accessToken(),
                rotatedPair.accessToken()
        );
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES)).thenReturn(
                firstPair.refreshToken(),
                secondPair.refreshToken(),
                rotatedPair.refreshToken()
        );
        when(jwtUtil.parseRefreshToken(firstPair.refreshToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.parseRefreshToken(secondPair.refreshToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.parseAccessToken(firstPair.accessToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.parseAccessToken(secondPair.accessToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.parseAccessToken(rotatedPair.accessToken())).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(firstPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(rotatedPair.refreshToken()))
                .thenReturn(NEW_REFRESH_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(firstPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(secondPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getTokenRemainingTime(rotatedPair.accessToken()))
                .thenReturn(NEW_ACCESS_TTL_MS);
        when(jwtUtil.getExpirationMs()).thenReturn(NEW_ACCESS_TTL_MS);
        return jwtUtil;
    }

    private JwtUtil concurrentJwtUtil(
            String oldRefreshToken,
            Long userId,
            ThreadLocal<TokenPair> threadCandidate,
            CountDownLatch bothCandidatesReady
    ) {
        JwtUtil jwtUtil = baseJwtUtil(oldRefreshToken, userId);
        when(jwtUtil.generateToken("cluster-user", userId, ROLES))
                .thenAnswer(invocation -> threadCandidate.get().accessToken());
        when(jwtUtil.generateRefreshToken("cluster-user", userId, ROLES))
                .thenAnswer(invocation -> threadCandidate.get().refreshToken());
        when(jwtUtil.getTokenRemainingTime(argThat(
                token -> token != null && token.startsWith("candidate-refresh-")
        ))).thenAnswer(invocation -> {
            bothCandidatesReady.countDown();
            if (!bothCandidatesReady.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Both refresh candidates were not generated");
            }
            return NEW_REFRESH_TTL_MS;
        });
        when(jwtUtil.getTokenRemainingTime(argThat(
                token -> token != null && token.startsWith("candidate-access-")
        ))).thenReturn(NEW_ACCESS_TTL_MS);
        return jwtUtil;
    }

    private JwtUtil baseJwtUtil(String oldRefreshToken, Long userId) {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(userId, "cluster-user", ROLES);
        when(jwtUtil.parseRefreshToken(oldRefreshToken)).thenReturn(Optional.of(payload));
        when(jwtUtil.getTokenRemainingTime(oldRefreshToken)).thenReturn(ROTATION_TTL_MS);
        return jwtUtil;
    }

    private TokenServiceImpl createTokenService(JwtUtil jwtUtil) {
        TokenServiceImpl tokenService = new TokenServiceImpl(jwtUtil, redisTemplate);
        ReflectionTestUtils.setField(tokenService, "refreshRetryTtlSeconds", RETRY_TTL_SECONDS);
        return tokenService;
    }

    private List<String> rotationKeys(String oldTokenHash, Long userId, TokenPair candidate) {
        List<String> keys = new ArrayList<>(oldRotationKeys(oldTokenHash, userId));
        String newRefreshHash = TokenHashUtil.hashToken(candidate.refreshToken());
        String newAccessHash = TokenHashUtil.hashToken(candidate.accessToken());
        keys.add("refresh:" + newRefreshHash);
        keys.add("user:tokens:" + userId + ":" + newRefreshHash);
        keys.add("user:access:" + userId + ":" + newAccessHash);
        keys.add("user:access:set:" + userId);
        keys.add("user:refresh:retry:set:" + userId);
        keys.add("refresh:retry:owner:" + newRefreshHash);
        keys.add("refresh:retry:owner:" + oldTokenHash);
        return keys;
    }

    private List<String> oldRotationKeys(String oldTokenHash, Long userIdValue) {
        String userId = userIdValue.toString();
        return new ArrayList<>(List.of(
                "refresh:" + oldTokenHash,
                "rotation:" + oldTokenHash,
                "refresh:retry:" + oldTokenHash,
                "user:tokens:" + userId + ":" + oldTokenHash,
                "user:refresh:set:" + userId
        ));
    }

    private List<String> candidateKeys(Long userId, TokenPair candidate) {
        String newRefreshHash = TokenHashUtil.hashToken(candidate.refreshToken());
        String newAccessHash = TokenHashUtil.hashToken(candidate.accessToken());
        return List.of(
                "refresh:" + newRefreshHash,
                "user:tokens:" + userId + ":" + newRefreshHash,
                "user:access:" + userId + ":" + newAccessHash,
                "user:access:set:" + userId,
                "refresh:retry:owner:" + newRefreshHash
        );
    }

    private List<String> cleanupKeys(
            List<String> rotationKeys,
            Long userId,
            String oldAccessHash,
            TokenPair candidate
    ) {
        List<String> cleanupKeys = new ArrayList<>(rotationKeys);
        cleanupKeys.add("user:access:" + userId + ":" + oldAccessHash);
        cleanupKeys.add("blacklist:" + oldAccessHash);
        cleanupKeys.add("blacklist:" + TokenHashUtil.hashToken(candidate.accessToken()));
        return cleanupKeys;
    }

    private void seedOldRefreshToken(List<String> keys, String oldTokenHash, Long userId) {
        seedOldRefreshToken(keys, oldTokenHash, userId, oldTokenHash);
    }

    private void seedOldRefreshToken(
            List<String> keys,
            String oldTokenHash,
            Long userId,
            String oldAccessHash
    ) {
        redisTemplate.opsForValue().set(
                keys.get(0),
                userId.toString(),
                ROTATION_TTL_MS,
                TimeUnit.MILLISECONDS
        );
        redisTemplate.opsForValue().set(
                keys.get(3),
                oldAccessHash,
                ROTATION_TTL_MS,
                TimeUnit.MILLISECONDS
        );
        redisTemplate.opsForSet().add(keys.get(4), oldTokenHash);
        redisTemplate.expire(keys.get(4), ROTATION_TTL_MS, TimeUnit.MILLISECONDS);
    }

    private void seedAccessToken(Long userId, String accessHash) {
        seedAccessToken(userId, accessHash, NEW_ACCESS_TTL_MS);
    }

    private void seedAccessToken(Long userId, String accessHash, long ttl) {
        String accessKey = "user:access:" + userId + ":" + accessHash;
        String accessSetKey = "user:access:set:" + userId;
        redisTemplate.opsForValue().set(
                accessKey,
                "active",
                ttl,
                TimeUnit.MILLISECONDS
        );
        redisTemplate.opsForSet().add(accessSetKey, accessHash);
        redisTemplate.expire(accessSetKey, ttl, TimeUnit.MILLISECONDS);
    }

    private Long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
    }
}
