package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.exception.RefreshInProgressException;
import com.warehouse.lock.DistributedLock;
import com.warehouse.lock.DistributedLockManager;
import com.warehouse.metric.MetricService;
import com.warehouse.security.UserPrincipal;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String REFRESH_LOCK_NAME_PREFIX = "refresh-rotation:";
    private static final long DEFAULT_REFRESH_LOCK_WAIT_TIMEOUT_MS = 5_000L;
    private static final long DEFAULT_REFRESH_LOCK_TTL_MS = 30_000L;
    private static final long DEFAULT_REFRESH_LOCK_RETRY_INTERVAL_MS = 50L;

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;
    private final MetricService metricService;
    private final DistributedLockManager distributedLockManager;

    @Value("${app.jwt.refresh-lock.wait-timeout-ms:5000}")
    private long refreshLockWaitTimeoutMs = DEFAULT_REFRESH_LOCK_WAIT_TIMEOUT_MS;

    @Value("${app.jwt.refresh-lock.ttl-ms:30000}")
    private long refreshLockTtlMs = DEFAULT_REFRESH_LOCK_TTL_MS;

    @Value("${app.jwt.refresh-lock.retry-interval-ms:50}")
    private long refreshLockRetryIntervalMs = DEFAULT_REFRESH_LOCK_RETRY_INTERVAL_MS;

    /**
     * {@inheritDoc}
     *
     * <p>При успешной аутентификации генерируется пара токенов:
     * <ul>
     *   <li>Access токен с коротким TTL (по умолчанию 1 день)</li>
     *   <li>Refresh токен с длинным TTL (по умолчанию 7 дней)</li>
     * </ul>
     * </p>
     *
     * @param request запрос с логином и паролем
     * @return ответ с токенами
     * @throws AuthenticationException если аутентификация не удалась
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        log.debug("Attempting login for user: {}", request.username());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

            if (!principal.isEnabled()) {
                throw new AuthenticationException("User account is deactivated") {
                };
            }

            List<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            TokenPair tokenPair = tokenService.generateTokenPair(
                    principal.getUsername(),
                    principal.getId(),
                    roles
            );

            log.info("User '{}' successfully authenticated", request.username());
            metricService.increment("warehouse.auth.login.success.total");

            return new LoginResponse(
                    tokenPair.accessToken(),
                    tokenPair.refreshToken(),
                    jwtUtil.getExpirationMs()
            );
        } catch (AuthenticationException e) {
            log.warn("Authentication failed for user '{}': {}", request.username(), e.getMessage());
            metricService.increment("warehouse.auth.login.failure.total");
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Процесс обновления:
     * <ol>
     *   <li>Проверка retry-результата завершенной ротации</li>
     *   <li>Кластерная сериализация одинаковых запросов</li>
     *   <li>Атомарная Redis-ротация и классификация повторного использования</li>
     * </ol>
     * </p>
     *
     * @param request запрос с refresh токеном и опционально старым access
     * @return ответ с новой парой токенов
     */
    @Override
    public RefreshResponse refresh(RefreshRequest request) {
        log.debug("Processing refresh token request");

        String refreshToken = request.refreshToken();

        Optional<TokenPair> cachedResult = tokenService.getRefreshRetryResult(refreshToken);
        if (cachedResult.isPresent()) {
            log.info("Refresh retry detected, returning cached tokens");
            return toRefreshResponse(cachedResult.get());
        }

        validateRefreshLockConfiguration();
        String lockName = REFRESH_LOCK_NAME_PREFIX + TokenHashUtil.hashToken(refreshToken);
        Duration lockTtl = Duration.ofMillis(refreshLockTtlMs);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(refreshLockWaitTimeoutMs);

        while (true) {
            Optional<DistributedLock> acquiredLock = distributedLockManager.tryAcquire(lockName, lockTtl);
            if (acquiredLock.isPresent()) {
                try (DistributedLock ignored = acquiredLock.get()) {
                    Optional<TokenPair> resultAfterLock = tokenService.getRefreshRetryResult(refreshToken);
                    if (resultAfterLock.isPresent()) {
                        log.info("Concurrent refresh completed, returning cached tokens");
                        return toRefreshResponse(resultAfterLock.get());
                    }
                    return rotateRefreshToken(refreshToken);
                }
            }

            Optional<TokenPair> resultWhileWaiting = tokenService.getRefreshRetryResult(refreshToken);
            if (resultWhileWaiting.isPresent()) {
                log.info("Concurrent refresh completed while waiting, returning cached tokens");
                return toRefreshResponse(resultWhileWaiting.get());
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            waitBeforeLockRetry(remainingNanos);
        }

        Optional<TokenPair> resultAfterTimeout = tokenService.getRefreshRetryResult(refreshToken);
        if (resultAfterTimeout.isPresent()) {
            return toRefreshResponse(resultAfterTimeout.get());
        }
        throw new RefreshInProgressException("Refresh token rotation is already in progress");
    }

    private RefreshResponse rotateRefreshToken(String refreshToken) {
        TokenPair newTokenPair = tokenService.rotateRefreshToken(refreshToken);
        log.info("Refresh token rotated atomically");

        return toRefreshResponse(newTokenPair);
    }

    private RefreshResponse toRefreshResponse(TokenPair tokenPair) {
        return new RefreshResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                jwtUtil.getExpirationMs()
        );
    }

    private void validateRefreshLockConfiguration() {
        if (refreshLockWaitTimeoutMs < 0) {
            throw new IllegalStateException("Refresh lock wait timeout must not be negative");
        }
        if (refreshLockTtlMs <= 0) {
            throw new IllegalStateException("Refresh lock TTL must be positive");
        }
        if (refreshLockRetryIntervalMs <= 0) {
            throw new IllegalStateException("Refresh lock retry interval must be positive");
        }
    }

    private void waitBeforeLockRetry(long remainingNanos) {
        long retryNanos = TimeUnit.MILLISECONDS.toNanos(refreshLockRetryIntervalMs);
        long sleepNanos = Math.min(retryNanos, remainingNanos);
        try {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefreshInProgressException("Interrupted while waiting for refresh token rotation");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>При выходе:
     * <ul>
     *   <li>Удаляется refresh токен из Redis</li>
     *   <li>Access токен добавляется в blacklist</li>
     * </ul>
     * </p>
     *
     * @param request запрос с access и refresh токенами
     */
    @Override
    public void logout(LogoutRequest request) {
        String refreshToken = request.refreshToken();
        String accessToken = request.accessToken();

        tokenService.revokeTokenPair(refreshToken, accessToken);
        log.info("Token pair revoked atomically");
        var payload = jwtUtil.parseRefreshToken(refreshToken);
        payload.ifPresent(p ->
                log.info("User '{}' logged out successfully", p.userId())
        );
    }
}
