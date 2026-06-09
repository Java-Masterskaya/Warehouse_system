package com.warehouse.controller.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.entity.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        // Логинимся как admin, чтобы использовать токен в тестах
        adminToken = obtainToken("admin", "secret");
    }

    // Получаем токен
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

    // Проверяем, что логин с верными данными возвращает токен и время жизни
    @Test
    void loginSuccess() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(86400000));
    }

    // Проверяем, что запрос без токена к защищённому эндпоинту возвращает 401
    @Test
    void accessProtectedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // Проверяем, что запрос с невалидным токеном возвращает 401
    @Test
    void accessProtectedWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer invalidtoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // Проверяем, что просроченный токен отклоняется с 401
    @Test
    void accessProtectedWithExpiredToken() throws Exception {
        String expiredToken = createExpiredToken("admin", List.of("ROLE_ADMIN"));
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // Проверяем, что health-эндпоинт доступен без аутентификации (статус не 401)
    @Test
    void healthAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(401)));
    }

    // Проверяем, что с валидным токеном запрос не возвращает 401
    @Test
    void accessProtectedWithValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is(not(401)));
    }

    // Проверяем, что пользователь с ролью USER не может выполнить действие, требующее ADMIN (получаем 403)
    @Test
    void accessAdminEndpointWithUserRoleShouldFail() throws Exception {
        UserCreateRequest userRequest = new UserCreateRequest();
        userRequest.setUsername("testuser");
        userRequest.setPassword("testpassword123");
        userRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userRequest)))
                .andExpect(status().isCreated());

        String userToken = obtainToken("testuser", "testpassword123");

        UserCreateRequest anotherRequest = new UserCreateRequest();
        anotherRequest.setUsername("another");
        anotherRequest.setPassword("anotherpassword123");
        anotherRequest.setRole(Role.ROLE_USER);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(anotherRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
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
