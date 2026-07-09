package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.metric.MetricService;
import com.warehouse.security.JwtUtil;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;
    private final MetricService metricService;

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

    @Override
    public RefreshResponse refresh(RefreshRequest request) {
        log.debug("Processing refresh token request");

        String refreshToken = request.refreshToken();

        // Validate refresh token
        if (!tokenService.validateRefreshToken(refreshToken)) {
            log.warn("Invalid or expired refresh token");
            throw new RuntimeException("Invalid refresh token");
        }

        // Check for token reuse
        if (tokenService.isRefreshTokenReused(refreshToken)) {
            log.warn("Refresh token reuse detected, revoking all tokens");
            // Revoke entire chain
            var payload = jwtUtil.parseRefreshToken(refreshToken);
            payload.ifPresent(p -> tokenService.revokeAllUserTokens(p.userId()));
            throw new RuntimeException("Token reuse detected");
        }

        // Parse refresh token
        var payload = jwtUtil.parseRefreshToken(refreshToken);
        if (payload.isEmpty()) {
            throw new RuntimeException("Invalid refresh token");
        }

        var p = payload.get();

        // Generate new token pair
        TokenPair newTokenPair = tokenService.generateTokenPair(
                p.username(),
                p.userId(),
                p.roles()
        );

        // Rotate refresh token
        tokenService.rotateRefreshToken(refreshToken, newTokenPair.refreshToken());

        log.info("Refresh token rotated for user: {}", p.userId());

        // Blacklist old access token
        String accessToken = request.accessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            tokenService.blacklistAccessToken(accessToken);
            log.info("Old access token blacklisted for user: {}", p.userId());
        } else {
            log.debug("No access token provided for blacklisting");
        }

        log.info("Refresh token rotated, access blacklisted for user: {}", p.userId());

        return new RefreshResponse(
                newTokenPair.accessToken(),
                newTokenPair.refreshToken(),
                jwtUtil.getExpirationMs()
        );
    }

    @Override
    public void logout(LogoutRequest request) {
        String refreshToken = request.refreshToken();
        String accessToken = request.accessToken();

            // Revoke refresh token
            tokenService.revokeRefreshToken(refreshToken);

            // Blacklist access token
            tokenService.blacklistAccessToken(accessToken);

        var payload = jwtUtil.parseRefreshToken(refreshToken);
        payload.ifPresent(p ->
                log.info("User '{}' logged out successfully", p.userId())
        );
    }
}