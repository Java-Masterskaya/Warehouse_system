package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Интеграционный тест HTTP-слоя: реальный Spring-контекст + Testcontainers (из AbstractIntegrationTest).
// Проверяет: статусы ответов, тело JSON, Security (401/403).
@AutoConfigureMockMvc
class ItemControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        // Получаем реальный токен через /api/auth/login — admin создаётся миграцией V5
        adminToken = obtainToken("admin", "secret");
        // USER нет в миграциях — генерируем токен напрямую.
        // JwtAuthFilter читает роли из claims, не обращаясь к БД, поэтому это корректно.
        userToken = jwtUtil.generateToken("testuser", List.of("ROLE_USER"));
    }

    // --- Acceptance criteria задачи #1 ---

    @Test
    void createItemAdminTokenReturns201WithBody() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-001", "Ноутбук Dell", "Электроника", 5);

        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-CTRL-001"))
                .andExpect(jsonPath("$.name").value("Ноутбук Dell"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void createItemDuplicateSkuReturns409() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-DUP", "Товар", "Категория", 0);

        // Создаём первый раз — успешно
        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Тот же SKU — должен вернуть 409
        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SKU"));
    }

    @Test
    void createItemNoTokenReturns401() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-002", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void createItemUserTokenReturns403() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-003", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    // --- Валидация входных данных ---

    @Test
    void createItemBlankSkuReturns400() throws Exception {
        String body = """
                {"sku": "", "name": "Товар", "category": "Категория", "minStock": 0}
                """;

        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void createItemNegativeMinStockReturns400() throws Exception {
        String body = """
                {"sku": "SKU-CTRL-004", "name": "Товар", "category": "Категория", "minStock": -1}
                """;

        mockMvc.perform(post("/api/v1/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    // Вспомогательный метод для получения токена через /api/auth/login
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
}
