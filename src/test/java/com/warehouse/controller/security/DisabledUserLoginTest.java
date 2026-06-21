package com.warehouse.controller.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест: проверка логина деактивированного пользователя.
 * Тестирует: дефолтный admin логинится, деактивированный пользователь получает 401.
 */
@AutoConfigureMockMvc
class DisabledUserLoginTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    /**
     * Дефолтный admin из миграции V5__insert_default_admin.sql логинится успешно.
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
     * Деактивированный пользователь (is_active = false) получает 401.
     */
    @Test
    void disabledUserLoginReturnsUnauthorized() throws Exception {
        String adminToken = obtainAdminToken();
        String username = "disabled_user_" + System.currentTimeMillis();
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

        User user = userRepository.findByUsername(username).orElseThrow();
        user.setActive(false);
        userRepository.save(user);

        LoginRequest loginRequest = new LoginRequest(username, password);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Вспомогательный метод для получения токена администратора.
     *
     * @return JWT токен администратора
     */
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
}
