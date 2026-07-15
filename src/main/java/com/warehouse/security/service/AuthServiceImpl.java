package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.TokenReuseException;
import com.warehouse.metric.MetricService;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.UserPrincipal;
import com.warehouse.security.model.TokenPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;
    private final MetricService metricService;

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
     *   <li>Проверка валидности refresh токена в Redis</li>
     *   <li>Проверка на повторное использование (защита от кражи)</li>
     *   <li>Генерация новой пары токенов</li>
     *   <li>Ротация refresh (старый удаляется, новый сохраняется)</li>
     *   <li>Опциональный blacklist старого access</li>
     * </ol>
     * </p>
     *
     * @param request запрос с refresh токеном и опционально старым access
     * @return ответ с новой парой токенов
     * @throws InvalidTokenException если refresh токен невалиден
     * @throws TokenReuseException   если обнаружено повторное использование refresh
     */
    @Override
    public RefreshResponse refresh(RefreshRequest request) {
        log.debug("Processing refresh token request");

        String refreshToken = request.refreshToken();

        // 1. СНАЧАЛА проверяем retry-cache
        Optional<TokenPair> cachedResult = tokenService.getRefreshRetryResult(refreshToken);
        if (cachedResult.isPresent()) {
            TokenPair cachedPair = cachedResult.get();
            log.info("Refresh retry detected, returning cached tokens");
            return new RefreshResponse(
                    cachedPair.accessToken(),
                    cachedPair.refreshToken(),
                    jwtUtil.getExpirationMs()
            );
        }

        // Check for token reuse
        if (tokenService.isRefreshTokenReused(refreshToken)) {
            log.warn("Refresh token reuse detected, revoking all tokens");
            // Revoke entire chain
            var payload = jwtUtil.parseRefreshToken(refreshToken);
            payload.ifPresent(p -> {
                tokenService.blacklistAllUserAccessTokens(p.userId());
                tokenService.revokeAllUserTokens(p.userId());
                log.warn("All tokens revoked for user: {} due to refresh token reuse", p.userId());
            });
            throw new TokenReuseException("Token reuse detected - all tokens revoked");
        }

        // Validate refresh token
        if (!tokenService.validateRefreshToken(refreshToken)) {
            log.warn("Invalid or expired refresh token");
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        // Parse refresh token
        var payload = jwtUtil.parseRefreshToken(refreshToken);
        if (payload.isEmpty()) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        var p = payload.get();

        // Blacklist ALL old access tokens
        tokenService.blacklistAllUserAccessTokens(p.userId());
        log.info("All old access tokens blacklisted for user: {}", p.userId());

        // Generate new token pair
        TokenPair newTokenPair = tokenService.generateTokenPair(
                p.username(),
                p.userId(),
                p.roles()
        );

        // Сохраняем результат в retry-cache (до ротации)
        tokenService.saveRefreshRetryResult(refreshToken, newTokenPair);

        // Rotate refresh token
        tokenService.rotateRefreshToken(refreshToken);
        log.info("Refresh token rotated for user: {}", p.userId());

        log.info("Refresh token rotated, access blacklisted for user: {}", p.userId());

        return new RefreshResponse(
                newTokenPair.accessToken(),
                newTokenPair.refreshToken(),
                jwtUtil.getExpirationMs()
        );
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

        // Revoke refresh token
        tokenService.revokeRefreshToken(refreshToken);
        log.info("Refresh token revoked");
        // Blacklist access token
        tokenService.blacklistAccessToken(accessToken);
        log.info("Access token blacklisted");
        var payload = jwtUtil.parseRefreshToken(refreshToken);
        payload.ifPresent(p ->
                log.info("User '{}' logged out successfully", p.userId())
        );
    }
}