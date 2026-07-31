package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.RefreshInProgressException;
import com.warehouse.exception.TokenReuseException;
import com.warehouse.lock.DistributedLock;
import com.warehouse.lock.DistributedLockManager;
import com.warehouse.metric.MetricService;
import com.warehouse.security.UserPrincipal;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.util.TokenHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private DistributedLockManager distributedLockManager;

    @Mock
    private DistributedLock distributedLock;

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
        lenient().when(distributedLockManager.tryAcquire(anyString(), any(Duration.class)))
                .thenReturn(Optional.of(distributedLock));
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
            when(loginAttemptService.checkAndConsume(TEST_USERNAME)).thenReturn(0L);
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
            verify(loginAttemptService).registerSuccess(TEST_USERNAME);
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
            when(loginAttemptService.checkAndConsume(TEST_USERNAME)).thenReturn(0L);
            when(authentication.getPrincipal()).thenReturn(inactiveUser);

            // Act & Assert
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageContaining("deactivated");

            verify(metricService).increment("warehouse.auth.login.failure.total");
            // registerSuccess вызывается до проверки isEnabled (см. AuthServiceImpl.login)
            verify(loginAttemptService).registerSuccess(TEST_USERNAME);
        }

        @Test
        @DisplayName("Should throw exception on invalid credentials")
        void loginWithInvalidCredentialsShouldThrowException() {
            // Arrange
            LoginRequest request = new LoginRequest(TEST_USERNAME, "wrong");
            when(loginAttemptService.checkAndConsume(TEST_USERNAME)).thenReturn(0L);
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
            TokenPair newTokenPair = new TokenPair(newAccessToken, newRefreshToken);

            RefreshRequest request = new RefreshRequest(oldRefreshToken);
            when(tokenService.rotateRefreshToken(oldRefreshToken)).thenReturn(newTokenPair);
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
            verify(tokenService, never()).blacklistAllUserAccessTokens(TEST_USER_ID);
            verify(tokenService, never()).isRefreshTokenReused(oldRefreshToken);
            verify(tokenService, never()).validateRefreshToken(oldRefreshToken);
            verify(jwtUtil, never()).parseRefreshToken(oldRefreshToken);

            ArgumentCaptor<String> lockNameCaptor = ArgumentCaptor.forClass(String.class);
            verify(distributedLockManager).tryAcquire(
                    lockNameCaptor.capture(),
                    eq(Duration.ofSeconds(30))
            );
            assertThat(lockNameCaptor.getValue())
                    .doesNotContain(oldRefreshToken)
                    .contains(TokenHashUtil.hashToken(oldRefreshToken));
            verify(distributedLock).close();
        }

        @Test
        @DisplayName("Should throw InvalidTokenException when refresh token is invalid")
        void refreshWithInvalidRefreshTokenShouldThrowException() {
            // Arrange
            String invalidRefreshToken = "invalid-token";
            RefreshRequest request = new RefreshRequest(invalidRefreshToken);

            when(tokenService.rotateRefreshToken(invalidRefreshToken))
                    .thenThrow(new InvalidTokenException("Invalid or expired refresh token"));

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
            RefreshRequest request = new RefreshRequest(reusedRefreshToken);
            when(tokenService.rotateRefreshToken(reusedRefreshToken))
                    .thenThrow(new TokenReuseException("Token reuse detected - all tokens revoked"));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(TokenReuseException.class)
                    .hasMessageContaining("Token reuse detected");

            verify(tokenService).rotateRefreshToken(reusedRefreshToken);
        }

        @Test
        @DisplayName("Should return cached tokens on retry")
        void refreshRetryShouldReturnCachedTokens() {
            // Arrange
            String refreshToken = "refresh-token";
            String accessToken = "new-access-token";
            String newRefreshToken = "new-refresh-token";
            TokenPair cachedPair = new TokenPair(accessToken, newRefreshToken);

            RefreshRequest request = new RefreshRequest(refreshToken);

            when(tokenService.getRefreshRetryResult(refreshToken))
                    .thenReturn(Optional.of(cachedPair));
            when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);

            // Act
            RefreshResponse response = authService.refresh(request);

            // Assert
            assertThat(response.accessToken()).isEqualTo(accessToken);
            assertThat(response.refreshToken()).isEqualTo(newRefreshToken);

            // Проверяем, что reuse НЕ проверялся
            verify(tokenService, never()).isRefreshTokenReused(anyString());
            verify(tokenService, never()).validateRefreshToken(anyString());
            verify(distributedLockManager, never()).tryAcquire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("Should recheck retry cache after acquiring distributed lock")
        void refreshShouldRecheckRetryCacheUnderLock() {
            String oldRefreshToken = "old-refresh-token";
            TokenPair cachedPair = new TokenPair("cached-access-token", "cached-refresh-token");
            RefreshRequest request = new RefreshRequest(oldRefreshToken);

            when(tokenService.getRefreshRetryResult(oldRefreshToken))
                    .thenReturn(Optional.empty(), Optional.of(cachedPair));
            when(jwtUtil.getExpirationMs()).thenReturn(EXPIRATION_MS);

            RefreshResponse response = authService.refresh(request);

            assertThat(response.accessToken()).isEqualTo(cachedPair.accessToken());
            assertThat(response.refreshToken()).isEqualTo(cachedPair.refreshToken());
            verify(tokenService, never()).rotateRefreshToken(anyString());
            verify(distributedLock).close();
        }

        @Test
        @DisplayName("Should stop waiting when refresh lock timeout is reached")
        void refreshShouldStopWaitingAtConfiguredTimeout() {
            String oldRefreshToken = "old-refresh-token";
            RefreshRequest request = new RefreshRequest(oldRefreshToken);
            ReflectionTestUtils.setField(authService, "refreshLockWaitTimeoutMs", 0L);
            when(distributedLockManager.tryAcquire(anyString(), any(Duration.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(RefreshInProgressException.class)
                    .hasMessageContaining("already in progress");

            verify(tokenService, never()).rotateRefreshToken(anyString());
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
            verify(tokenService).revokeTokenPair(refreshToken, accessToken);
        }
    }

}
