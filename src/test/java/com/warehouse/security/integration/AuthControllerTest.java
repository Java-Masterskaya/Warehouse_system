package com.warehouse.security.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import com.warehouse.security.service.TokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Auth Integration Tests")
@Slf4j
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenService tokenService;

    private String adminToken;
    private String adminRefreshToken;
    private String userToken;
    private String userRefreshToken;

    @BeforeEach
    void setUp() throws Exception {
        // Login as admin and get tokens
        LoginResponse adminLogin = loginAndGetTokens("admin", "secret");
        adminToken = adminLogin.accessToken();
        adminRefreshToken = adminLogin.refreshToken();

        // Create test user if not exists
        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        // Generate tokens for test user
        LoginResponse userLogin = loginAndGetTokens("testuser", "password");
        userToken = userLogin.accessToken();
        userRefreshToken = userLogin.refreshToken();
    }

    // ==================== LOGIN TESTS ====================

    @Test
    @DisplayName("Login success returns access and refresh tokens")
    void loginSuccessReturnsTokens() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    @Test
    @DisplayName("Login with wrong password returns 401")
    void loginWrongPasswordReturns401() throws Exception {
        LoginRequest request = new LoginRequest("admin", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Login with unknown user returns 401")
    void loginUnknownUserReturns401() throws Exception {
        LoginRequest request = new LoginRequest("nobody", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Disabled user login returns 401")
    void disabledUserLoginReturnsUnauthorized() throws Exception {
        String username = "disabled_user_" + System.currentTimeMillis();
        String password = "password123";

        // Create user
        UserCreateRequest createRequest = new UserCreateRequest();
        createRequest.setUsername(username);
        createRequest.setPassword(password);
        createRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());

        // Deactivate user
        User user = userRepository.findByUsername(username).orElseThrow();
        mockMvc.perform(delete("/api/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Try to login
        LoginRequest loginRequest = new LoginRequest(username, password);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // ==================== TOKEN VALIDATION TESTS ====================

    @Test
    @DisplayName("Access protected endpoint without token returns 401")
    void accessProtectedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Access protected endpoint with invalid token returns 401")
    void accessProtectedWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer invalidtoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Access protected endpoint with expired token returns 401")
    void accessProtectedWithExpiredToken() throws Exception {
        String expiredToken = createExpiredToken("admin", List.of("ROLE_ADMIN"));
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Health endpoint accessible without token")
    void healthAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(401)));
    }

    @Test
    @DisplayName("Access protected endpoint with valid token returns OK")
    void accessProtectedWithValidToken() throws Exception {
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("User cannot access admin endpoint - 403")
    void accessAdminEndpointWithUserRoleShouldFail() throws Exception {
        String uniqueUsername = "testuser_for_role_test_" + System.currentTimeMillis();
        UserCreateRequest userRequest = new UserCreateRequest();
        userRequest.setUsername(uniqueUsername);
        userRequest.setPassword("testpassword123");
        userRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userRequest)))
                .andExpect(status().isCreated());

        // Login as new user
        LoginResponse userLogin = loginAndGetTokens(uniqueUsername, "testpassword123");
        String userTokenForTest = userLogin.accessToken();

        UserCreateRequest anotherRequest = new UserCreateRequest();
        anotherRequest.setUsername("another");
        anotherRequest.setPassword("anotherpassword123");
        anotherRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + userTokenForTest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(anotherRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    // ==================== REFRESH FLOW TESTS ====================

    void refreshToken_shouldReturnNewAccessToken() throws Exception {
        // 1. Сохраняем старые токены
        String oldAccessToken = userToken;
        String oldRefreshToken = userRefreshToken;

        log.info("Old access token: {}", oldAccessToken.substring(0, 20) + "...");
        log.info("Old refresh token: {}", oldRefreshToken.substring(0, 20) + "...");

        // 2. Отправляем refresh запрос
        RefreshRequest request = new RefreshRequest(userToken, userRefreshToken);
        String response = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        RefreshResponse refreshResponse = objectMapper.readValue(response, RefreshResponse.class);

        log.info("New access token: {}", refreshResponse.accessToken().substring(0, 20) + "...");
        log.info("New refresh token: {}", refreshResponse.refreshToken().substring(0, 20) + "...");

        // 3. Проверяем, что новые токены отличаются от старых
        assertThat(refreshResponse.accessToken())
                .as("New access token should be different")
                .isNotEqualTo(oldAccessToken);
        assertThat(refreshResponse.refreshToken())
                .as("New refresh token should be different")
                .isNotEqualTo(oldRefreshToken);

        // 4. Проверяем, что новый access токен работает
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + refreshResponse.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Old refresh token should not work after rotation")
    void oldRefreshToken_shouldNotWorkAfterRotation() throws Exception {
        // 1. Сохраняем старый refresh
        String oldRefresh = userRefreshToken;
        String oldAccess = userToken;

        // 2. Первый refresh - получаем новые токены
        RefreshRequest firstRequest = new RefreshRequest(oldAccess, oldRefresh);
        String firstResponse = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        RefreshResponse firstTokens = objectMapper.readValue(firstResponse, RefreshResponse.class);

        log.info("New access token: {}", firstTokens.accessToken());
        log.info("New refresh token: {}", firstTokens.refreshToken());

        // 3. Проверяем, что новые токены отличаются от старых
        assertThat(firstTokens.accessToken())
                .as("New access token should be different from old")
                .isNotEqualTo(oldAccess);
        assertThat(firstTokens.refreshToken())
                .as("New refresh token should be different from old")
                .isNotEqualTo(oldRefresh);

        // 4. Пытаемся использовать старый refresh - должен вернуть INVALID_TOKEN (он удален из Redis)
        RefreshRequest secondRequest = new RefreshRequest(firstTokens.accessToken(), oldRefresh);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REUSE"));
    }


    @Test
    @DisplayName("Refresh token reuse should revoke all tokens")
    void refreshTokenReuse_shouldRevokeAllTokens() throws Exception {
        // 1. Получаем новые токены через refresh
        RefreshRequest firstRequest = new RefreshRequest(userToken, userRefreshToken);
        String firstResponse = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        RefreshResponse firstTokens = objectMapper.readValue(firstResponse, RefreshResponse.class);

        // 2. Попытка использовать ТОТ ЖЕ refresh токен (reuse)
        //    Должен вернуть TOKEN_REUSE
        RefreshRequest reuseRequest = new RefreshRequest(firstTokens.accessToken(), userRefreshToken);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reuseRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REUSE"));

        // 3. Проверяем, что новый access токен тоже отозван
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + firstTokens.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    // ==================== LOGOUT TESTS ====================

    @Test
    @DisplayName("Logout should revoke tokens")
    void logout_shouldRevokeTokens() throws Exception {
        // Проверяем, что токен работает
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        // Логаут
        LogoutRequest logoutRequest = new LogoutRequest(userToken, userRefreshToken);
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk());

        // Access token должен быть blacklisted
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Refresh token должен быть revoked
        RefreshRequest refreshRequest = new RefreshRequest(null, userRefreshToken);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    // ==================== DEACTIVATION TESTS ====================

    @Test
    @DisplayName("Deactivated user loses access immediately")
    void deactivatedUser_losesAccessImmediately() throws Exception {
        // Создаем пользователя
        String username = "deactivation_test_" + System.currentTimeMillis();
        String password = "password123";

        UserCreateRequest createRequest = new UserCreateRequest();
        createRequest.setUsername(username);
        createRequest.setPassword(password);
        createRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());

        // Логинимся
        LoginResponse userLogin = loginAndGetTokens(username, password);
        String userTokenForTest = userLogin.accessToken();
        String userRefreshForTest = userLogin.refreshToken();

        // Получаем ID пользователя
        User user = userRepository.findByUsername(username).orElseThrow();

        // Проверяем, что токен работает
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + userTokenForTest))
                .andExpect(status().isOk());

        // Проверяем, что refresh токен валиден до деактивации
        boolean isValidBefore = tokenService.validateRefreshToken(userRefreshForTest);
        assertTrue(isValidBefore, "Refresh token should be valid before deactivation");

        // Деактивируем пользователя через DELETE
        mockMvc.perform(delete("/api/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Проверяем, что refresh токен стал невалидным после деактивации
        boolean isValidAfter = tokenService.validateRefreshToken(userRefreshForTest);
        assertFalse(isValidAfter, "Refresh token should be revoked after deactivation");

        // Access token должен быть blacklisted мгновенно
        mockMvc.perform(get("/api/items")
                        .header("Authorization", "Bearer " + userTokenForTest))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Refresh token должен быть revoked (возвращать 401)
        RefreshRequest refreshRequest = new RefreshRequest(null, userRefreshForTest);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    // ==================== HELPER METHODS ====================

    private LoginResponse loginAndGetTokens(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(response, LoginResponse.class);
    }

    private String createExpiredToken(String username, List<String> roles) {
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .issuedAt(Date.from(past.minusSeconds(3600)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}