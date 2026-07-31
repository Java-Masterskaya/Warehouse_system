package com.warehouse.security.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.lock.DistributedLockManager;
import com.warehouse.metric.MetricService;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.service.AuthServiceImpl;
import com.warehouse.security.service.LoginAttemptService;
import com.warehouse.security.service.TokenService;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DisplayName("Refresh rotation cluster integration tests")
class RefreshRotationClusterIntegrationTest extends AbstractIntegrationTest {

    private static final long EXPIRATION_MS = 86_400_000L;
    private static final long GENERATION_DELAY_MS = 150L;
    private static final long TEST_TIMEOUT_SECONDS = 10L;

    @Autowired
    private DistributedLockManager lockManager;

    @Test
    @DisplayName("Concurrent replicas must return the same cached rotation result")
    void concurrentReplicasMustReturnSameCachedResult() throws Exception {
        String oldRefreshToken = "cluster-refresh-" + UUID.randomUUID();
        TokenPair rotatedPair = new TokenPair("shared-access-token", "shared-refresh-token");
        AtomicReference<TokenPair> retryCache = new AtomicReference<>();
        AtomicInteger generationCalls = new AtomicInteger();
        CountDownLatch bothReplicasReadEmptyCache = new CountDownLatch(2);

        JwtUtil jwtUtil = mock(JwtUtil.class);
        TokenService tokenService = mock(TokenService.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        MetricService metricService = mock(MetricService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);

        when(tokenService.getRefreshRetryResult(oldRefreshToken))
                .thenAnswer(invocation -> readRetryCache(retryCache, bothReplicasReadEmptyCache));
        when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);
        when(tokenService.rotateRefreshToken(oldRefreshToken))
                .thenAnswer(invocation -> rotateOnce(generationCalls, retryCache, rotatedPair));

        AuthServiceImpl firstReplica = new AuthServiceImpl(
                authenticationManager,
                jwtUtil,
                tokenService,
                metricService,
                loginAttemptService,
                lockManager
        );
        AuthServiceImpl secondReplica = new AuthServiceImpl(
                authenticationManager,
                jwtUtil,
                tokenService,
                metricService,
                loginAttemptService,
                lockManager
        );

        RefreshRequest request = new RefreshRequest(oldRefreshToken);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefreshResponse> firstResult = executor.submit(() -> {
                start.await();
                return firstReplica.refresh(request);
            });
            Future<RefreshResponse> secondResult = executor.submit(() -> {
                start.await();
                return secondReplica.refresh(request);
            });

            start.countDown();
            RefreshResponse firstResponse = firstResult.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            RefreshResponse secondResponse = secondResult.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(firstResponse.accessToken()).isEqualTo(rotatedPair.accessToken());
            assertThat(firstResponse.refreshToken()).isEqualTo(rotatedPair.refreshToken());
            assertThat(secondResponse).isEqualTo(firstResponse);
            assertThat(generationCalls).hasValue(1);
            verify(tokenService, times(1)).rotateRefreshToken(oldRefreshToken);
        } finally {
            executor.shutdownNow();
        }
    }

    private Optional<TokenPair> readRetryCache(
            AtomicReference<TokenPair> retryCache,
            CountDownLatch bothReplicasReadEmptyCache
    ) throws InterruptedException {
        TokenPair cached = retryCache.get();
        if (cached != null) {
            return Optional.of(cached);
        }

        bothReplicasReadEmptyCache.countDown();
        if (!bothReplicasReadEmptyCache.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Both replicas did not start refresh in time");
        }
        return Optional.ofNullable(retryCache.get());
    }

    private TokenPair rotateOnce(
            AtomicInteger generationCalls,
            AtomicReference<TokenPair> retryCache,
            TokenPair rotatedPair
    )
            throws InterruptedException {
        generationCalls.incrementAndGet();
        TimeUnit.MILLISECONDS.sleep(GENERATION_DELAY_MS);
        retryCache.set(rotatedPair);
        return rotatedPair;
    }
}
