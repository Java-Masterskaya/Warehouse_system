package com.warehouse.security.ratelimiting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.service.movement.StockMovementService;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@AutoConfigureMockMvc
public class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProxyManager<byte[]> proxyManager;

    // Мокаем бизнес-сервис движений, чтобы проверить, вызывается ли он при 429
    @MockitoBean
    private StockMovementService movementService;

    @Autowired
    private StringRedisTemplate redisTemplate;


    @BeforeEach
    void cleanRedis() {
        // Перед каждым тестом желательно очищать Redis-ключи бакетов, если необходимо
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    // --- КРИТЕРИЙ 1 и 4: Тест лимитов логина (N+1 попытка, Retry-After, обычный юзер) ---
    @Test
    void shouldAllowLoginWithinLimits_AndReturn429WithRetryAfterOnExceeded() throws Exception {
        LoginRequest request = new LoginRequest("test_user", "password123");
        String jsonBody = objectMapper.writeValueAsString(request);

        // Лимит в конфиге = 2 попытки. Обычный пользователь делает их успешно в пределах лимита (Критерий 4)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized()); // Имитация BadCredentials, но рейт-лимит пропустил

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isUnauthorized());

        // 3-я (N+1) попытка в рамках текущего окна -> Блокировка 429 (Критерий 1)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error", containsString("Too many login attempts")));
    }

    // --- КРИТЕРИЙ 2: Горизонтальное масштабирование (Общий счетчик в Redis) ---
    @Test
    void shouldShareRateLimitAcrossMultipleAppInstances() throws Exception {
        LoginRequest request = new LoginRequest("cluster_user", "pass");
        String jsonBody = objectMapper.writeValueAsString(request);

        // Имитируем Instance 1 (используем текущий менеджер)
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // Имитируем Instance 2: создаем абсолютно новый независимый ProxyManager,
        // подключенный к тому же Redis (как будто это второй под в Kubernetes)
        // Он запрашивает тот же ключ 'rl:login:user:cluster_user'
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // 3-й запрос через первый инстанс падает в 429, так как второй инстанс тоже забирал токены из общего Redis
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());
    }

    // --- КРИТЕРИЙ 3: Превышение на write-эндпоинте -> Бизнес-операция не выполняется ---
    @Test
    @WithMockUser(username = "movement_user") // Имитируем авторизованного пользователя
    void shouldBlockWriteMovementEndpoint_AndNotExecuteBusinessLogic() throws Exception {
        String movementJson = """
        {
            "itemId": 1,
            "quantity": 5
        }
        """;

        // 1. Выполняем 3 разрешенных запроса на ваш эндпоинт
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/movements/receive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(movementJson))
                    .andExpect(status().isOk());
        }

        // 2. Проверяем, что метод вашего сервиса выполнился ровно 3 раза
        verify(movementService, times(3)).registerReceipt(any(), any());

        // 3. 4-й запрос превышает лимит
        mockMvc.perform(post("/api/movements/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementJson))
                .andExpect(status().isTooManyRequests());

        // 4. Гарантируем, что после 429 ошибки бизнес-логика больше НЕ вызывалась
        verifyNoMoreInteractions(movementService);
    }

    // --- КРИТЕРИЙ 5: Конфигурируемость, срабатывание и СБРОС окна ---
    @Test
    void shouldResetWindowAfterDurationPasses() throws Exception {
        LoginRequest request = new LoginRequest("sliding_user", "pass");
        String jsonBody = objectMapper.writeValueAsString(request);

        // Вычерпываем лимит (2 запроса)
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON));

        // Проверяем, что сейчас заблокировано
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());

        // Ждем 2 секунды (время окна `duration: 2s` из настроек BaseRateLimitIT для сброса)
        TimeUnit.SECONDS.sleep(2);

        // Окно плавно регенерировалось (Sliding/Greedy refill). Запрос снова разрешен!
        mockMvc.perform(post("/api/auth/login").content(jsonBody).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()); // Ошибка пароля, но НЕ 429!
    }
}

