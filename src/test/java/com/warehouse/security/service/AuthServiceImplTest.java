package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.TokenReuseException;
import com.warehouse.metric.MetricService;
import com.warehouse.security.JwtUtil;
import com.warehouse.security.UserPrincipal;
import com.warehouse.security.model.TokenPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthService Unit Tests")
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenService tokenService;

    @Mock
    private MetricService metricService;

    @InjectMocks
    private AuthServiceImpl authService;

    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "password";
    private static final Long TEST_USER_ID = 1L;
    private static final List<String> TEST_ROLES = List.of("ROLE_USER");
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final long EXPIRATION_MS = 900000L;

    private UserPrincipal userPrincipal;

    @BeforeEach
    void setUp() {
        userPrincipal = new UserPrincipal(
                TEST_USER_ID,
                TEST_USERNAME,
                "encoded-password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Nested
    @DisplayName("Login Tests")
    class LoginTests {

        @Test
        @DisplayName("Should return tokens on successful login")
        void loginSuccessShouldReturnTokens() {
            // Arrange
            LoginRequest request = new LoginRequest(TEST_USERNAME, TEST_PASSWORD);
            Authentication authentication = mock(Authentication.class);

            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(authentication.getPrincipal()).thenReturn(userPrincipal);
            when(authentication.getAuthorities())
                    .thenReturn((Collection) List.of(new SimpleGrantedAuthority("ROLE_USER")));
            when(tokenService.generateTokenPair(TEST_USERNAME, TEST_USER_ID, TEST_ROLES))
                    .thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));
            when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);

            // Act
            LoginResponse response = authService.login(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
            assertThat(response.expiresIn()).isEqualTo(EXPIRATION_MS);

            verify(metricService).increment("warehouse.auth.login.success.total");
        }

        @Test
        @DisplayName("Should throw exception when user is deactivated")
        void loginWhenUserDeactivatedShouldThrowException() {
            // Arrange
            LoginRequest request = new LoginRequest(TEST_USERNAME, TEST_PASSWORD);
            UserPrincipal inactiveUser = new UserPrincipal(
                    TEST_USER_ID,
                    TEST_USERNAME,
                    "encoded",
                    false,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

            Authentication authentication = mock(Authentication.class);
            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(authentication.getPrincipal()).thenReturn(inactiveUser);

            // Act & Assert
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageContaining("deactivated");

            verify(metricService).increment("warehouse.auth.login.failure.total");
        }

        @Test
        @DisplayName("Should throw exception on invalid credentials")
        void loginWithInvalidCredentialsShouldThrowException() {
            // Arrange
            LoginRequest request = new LoginRequest(TEST_USERNAME, "wrong");
            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenThrow(new AuthenticationException("Bad credentials") {});

            // Act & Assert
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(AuthenticationException.class);

            verify(metricService).increment("warehouse.auth.login.failure.total");
        }
    }

    @Nested
    @DisplayName("Refresh Tests")
    class RefreshTests {

        @Test
        @DisplayName("Should refresh tokens successfully")
        void refreshValidRefreshTokenShouldReturnNewTokens() {
            // Arrange
            String oldRefreshToken = "old-refresh-token";
            String newAccessToken = "new-access-token";
            String newRefreshToken = "new-refresh-token";

            RefreshRequest request = new RefreshRequest(ACCESS_TOKEN, oldRefreshToken);
            JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(
                    TEST_USER_ID, TEST_USERNAME, TEST_ROLES
            );

            when(tokenService.validateRefreshToken(oldRefreshToken)).thenReturn(true);
            when(tokenService.isRefreshTokenReused(oldRefreshToken)).thenReturn(false);
            when(jwtUtil.parseRefreshToken(oldRefreshToken)).thenReturn(Optional.of(payload));
            when(tokenService.generateTokenPair(TEST_USERNAME, TEST_USER_ID, TEST_ROLES))
                    .thenReturn(new TokenPair(newAccessToken, newRefreshToken));
            when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);

            // Act
            RefreshResponse response = authService.refresh(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo(newAccessToken);
            assertThat(response.refreshToken()).isEqualTo(newRefreshToken);
            assertThat(response.expiresIn()).isEqualTo(EXPIRATION_MS);

            // Verify rotation
            verify(tokenService).rotateRefreshToken(oldRefreshToken);
            verify(tokenService).blacklistAllUserAccessTokens(TEST_USER_ID);
        }

        @Test
        @DisplayName("Should throw InvalidTokenException when refresh token is invalid")
        void refreshWithInvalidRefreshTokenShouldThrowException() {
            // Arrange
            String invalidRefreshToken = "invalid-token";
            RefreshRequest request = new RefreshRequest(null, invalidRefreshToken);

            when(tokenService.validateRefreshToken(invalidRefreshToken)).thenReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("Invalid or expired refresh token");
        }

        @Test
        @DisplayName("Should revoke all tokens when refresh reuse detected")
        void refreshWithReusedRefreshTokenShouldRevokeAllTokens() {
            // Arrange
            String reusedRefreshToken = "reused-refresh-token";
            RefreshRequest request = new RefreshRequest(null, reusedRefreshToken);
            JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(
                    TEST_USER_ID, TEST_USERNAME, TEST_ROLES
            );

            when(tokenService.isRefreshTokenReused(reusedRefreshToken)).thenReturn(true);
            when(jwtUtil.parseRefreshToken(reusedRefreshToken)).thenReturn(Optional.of(payload));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(TokenReuseException.class)
                    .hasMessageContaining("Token reuse detected");

            // Verify all tokens revoked
            verify(tokenService).blacklistAllUserAccessTokens(TEST_USER_ID);
            verify(tokenService).revokeAllUserTokens(TEST_USER_ID);
        }

        @Test
        @DisplayName("Should handle refresh when no access token provided")
        void refreshWithoutAccessTokenShouldStillWork() {
            // Arrange
            String oldRefreshToken = "old-refresh-token";
            String newAccessToken = "new-access-token";
            String newRefreshToken = "new-refresh-token";

            RefreshRequest request = new RefreshRequest(null, oldRefreshToken);
            JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(
                    TEST_USER_ID, TEST_USERNAME, TEST_ROLES
            );

            when(tokenService.validateRefreshToken(oldRefreshToken)).thenReturn(true);
            when(tokenService.isRefreshTokenReused(oldRefreshToken)).thenReturn(false);
            when(jwtUtil.parseRefreshToken(oldRefreshToken)).thenReturn(Optional.of(payload));
            when(tokenService.generateTokenPair(TEST_USERNAME, TEST_USER_ID, TEST_ROLES))
                    .thenReturn(new TokenPair(newAccessToken, newRefreshToken));
            when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);

            // Act
            RefreshResponse response = authService.refresh(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo(newAccessToken);

            // Verify blacklist NOT called for access token
            verify(tokenService, never()).blacklistAccessToken(any());
            verify(tokenService).rotateRefreshToken(oldRefreshToken);
        }
    }

    @Nested
    @DisplayName("Logout Tests")
    class LogoutTests {

        @Test
        @DisplayName("Should revoke tokens on logout")
        void logoutShouldRevokeTokens() {
            // Arrange
            String refreshToken = "refresh-token";
            String accessToken = "access-token";
            LogoutRequest request = new LogoutRequest(accessToken, refreshToken);
            JwtUtil.JwtPayload payload = new JwtUtil.JwtPayload(
                    TEST_USER_ID, TEST_USERNAME, TEST_ROLES
            );

            when(jwtUtil.parseRefreshToken(refreshToken)).thenReturn(Optional.of(payload));

            // Act
            authService.logout(request);

            // Assert
            verify(tokenService).revokeRefreshToken(refreshToken);
            verify(tokenService).blacklistAccessToken(accessToken);
        }
    }

}