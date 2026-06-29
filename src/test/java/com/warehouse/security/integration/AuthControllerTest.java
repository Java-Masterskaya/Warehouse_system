package com.warehouse.security.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки эндпоинтов авторизации.
 * Тестирует логин, валидацию токенов, доступ к защищённым эндпоинтам.
 */
@AutoConfigureMockMvc
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

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "secret");

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));
    }

    // --- Basic Auth Tests ---

    /**
     * ADMIN может успешно залогиниться и получить токен.
     */
    @Test
    void loginSuccessReturnsToken() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    /**
     * Неверный пароль возвращает 401 Unauthorized.
     */
    @Test
    void loginWrongPasswordReturns401() throws Exception {
        LoginRequest request = new LoginRequest("admin", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Неизвестный пользователь возвращает 401 Unauthorized.
     */
    @Test
    void loginUnknownUserReturns401() throws Exception {
        LoginRequest request = new LoginRequest("nobody", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // --- Token Validation Tests ---

    /**
     * Запрос без токена к защищённому эндпоинту возвращает 401.
     */
    @Test
    void accessProtectedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Запрос с невалидным токеном возвращает 401.
     */
    @Test
    void accessProtectedWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer invalidtoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Просроченный токен возвращает 401.
     */
    @Test
    void accessProtectedWithExpiredToken() throws Exception {
        String expiredToken = createExpiredToken("admin", List.of("ROLE_ADMIN"));
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Health-эндпоинт доступен без аутентификации.
     */
    @Test
    void healthAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(401)));
    }

    /**
     * Валидный токен позволяет получить доступ к защищённому эндпоинту.
     */
    @Test
    void accessProtectedWithValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is(not(401)));
    }

    /**
     * Пользователь с ролью USER не может выполнить действие, требующее ADMIN (403).
     */
    @Test
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

        String userTokenForTest = obtainToken(uniqueUsername, "testpassword123");

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

    // --- Disabled User Tests ---

    /**
     * Дефолтный admin из миграции успешно логинится.
     */
    @Test
    void defaultAdminLoginReturnsToken() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Деактивированный пользователь получает 401 Unauthorized.
     */
    @Test
    void disabledUserLoginReturnsUnauthorized() throws Exception {
        String adminTokenForTest = obtainAdminToken();
        String username = "disabled_user_" + System.currentTimeMillis();
        String password = "password123";

        UserCreateRequest createRequest = new UserCreateRequest();
        createRequest.setUsername(username);
        createRequest.setPassword(password);
        createRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminTokenForTest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());

        User user = userRepository.findByUsername(username).orElseThrow();
        user.setActive(false);
        userRepository.save(user);

        LoginRequest loginRequest = new LoginRequest(username, password);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    // --- Вспомогательные методы ---

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String obtainAdminToken() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
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
