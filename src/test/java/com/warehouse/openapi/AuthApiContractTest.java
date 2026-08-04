package com.warehouse.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.security.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что ответы {@code /api/auth/login} соответствуют зафиксированному
 * OpenAPI-контракту: успех (200), неверные креды (401), ошибка валидации (400)
 * и блокировка по rate-limiting (429) должны совпадать со схемами
 * из {@code src/test/resources/openapi/warehouse.json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiContractTest extends AbstractOpenApiContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginSuccessMatchesContract() throws Exception {
        LoginRequest request = new LoginRequest("admin", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }

    @Test
    void loginWrongPasswordMatchesContract() throws Exception {
        LoginRequest request = new LoginRequest("admin", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(openApiContract());
    }

    @Test
    void loginBlankUsernameMatchesContract() throws Exception {
        String body = """
                {"username": "", "password": "secret"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(openApiResponseContract());
    }

    @Test
    void loginRateLimitedMatchesContract() throws Exception {
        String uniqueUsername = "contract_rl_user_" + System.currentTimeMillis();
        LoginRequest request = new LoginRequest(uniqueUsername, "wrongpassword");
        String jsonBody = objectMapper.writeValueAsString(request);

        // Тестовый лимит для логина по username = 2 попытки (см. AbstractIntegrationTest).
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(openApiContract());
    }
}
