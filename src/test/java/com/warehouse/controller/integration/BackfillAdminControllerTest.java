package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для админ-эндпоинтов backfill-операций.
 * <p>
 * Проверяет авторизацию (только ADMIN) и корректность ответов
 * для {@link com.warehouse.controller.BackfillAdminController}.
 */
@SpringBootTest
class BackfillAdminControllerTest extends AbstractIntegrationTest {

    private static final String BACKFILL_URL = "/admin/backfill/barcode";
    private static final String BACKFILL_STATUS_URL = "/admin/backfill/barcode/status";
    private static final String BACKFILL_STOP_URL = "/admin/backfill/barcode/stop";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtil.generateToken("backfill-admin", -101L, java.util.List.of("ROLE_ADMIN"));
        userToken = jwtUtil.generateToken("backfill-user", -102L, java.util.List.of("ROLE_USER"));
    }

    /**
     * ADMIN может запустить backfill: эндпоинт асинхронный — сразу отдаёт 202
     * и не ждёт завершения джобы (OPS-5, чтобы не держать HTTP-поток на больших таблицах).
     */
    @Test
    void runBackfillAsAdminReturns202Accepted() throws Exception {
        mockMvc.perform(post(BACKFILL_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("batchSize", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.batchSize").value(100));
    }

    /**
     * batchSize=0 отклоняется валидацией (400), а не падает 500-кой из PageRequest.
     */
    @Test
    void runBackfillWithInvalidBatchSizeReturns400() throws Exception {
        mockMvc.perform(post(BACKFILL_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("batchSize", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    /**
     * ADMIN может посмотреть статус backfill-джобы.
     */
    @Test
    void backfillStatusAsAdminReturns200() throws Exception {
        mockMvc.perform(get(BACKFILL_STATUS_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").isBoolean());
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

}
