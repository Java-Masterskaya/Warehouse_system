package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для админ-эндпоинтов backfill-операций.
 * <p>
 * Проверяет авторизацию (только ADMIN) и корректность ответов
 * для {@link com.warehouse.controller.BackfillAdminController}.
 */
class BackfillAdminControllerTest extends AbstractIntegrationTest {

    private static final String BACKFILL_URL = "/admin/backfill/barcode";
    private static final String BACKFILL_STOP_URL = "/admin/backfill/barcode/stop";

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
        adminToken = obtainToken("admin", "secret");

        // Генерируем токен для обычного пользователя (username=testuser создаётся в data.sql)
        userToken = jwtUtil.generateToken("testuser", 2L, java.util.List.of("ROLE_USER"));
    }

    /**
     * ADMIN может запустить backfill и получить сводку выполнения.
     */
    @Test
    void runBackfillAsAdminReturns200WithSummary() throws Exception {
        mockMvc.perform(post(BACKFILL_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("batchSize", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.rowsProcessed").isNumber())
                .andExpect(jsonPath("$.lastId").isNumber())
                .andExpect(jsonPath("$.iterations").isNumber());
    }

    /**
     * ADMIN может запросить остановку работающей джобы.
     */
    @Test
    void stopBackfillAsAdminReturns200WithMessage() throws Exception {
        mockMvc.perform(post(BACKFILL_STOP_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isString());
    }

    /**
     * USER не может запустить backfill — доступ запрещён (403).
     */
    @Test
    void runBackfillAsUserReturns403() throws Exception {
        mockMvc.perform(post(BACKFILL_URL)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена на запуск backfill возвращает 401 Unauthorized.
     */
    @Test
    void runBackfillNoTokenReturns401() throws Exception {
        mockMvc.perform(post(BACKFILL_URL)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
