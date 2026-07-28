package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.IdempotencyKeyRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки эндпоинта управления движениями товаров.
 * Тестирует API для регистрации прихода товара на склад.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockMovementControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CategoryRepository categoryRepository;

    private String adminToken;
    private String userToken;
    private Item testItem;
    private Long testItemId;
    private Category testCategory;

    @BeforeEach
    void setUp() throws Exception {
        String uniqueSku = "SKU-MOV-" + System.currentTimeMillis();
        testCategory = categoryRepository.findByNameIgnoreCase("Категория")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Категория")
                                .build()
                ));
        testItem = new Item();
        testItem.setSku(uniqueSku);
        testItem.setName("Тестовый товар");
        testItem.setCategory(testCategory);
        testItem.setMinStock(5);
        testItem.setActive(true);
        testItem.setPrice(BigDecimal.valueOf(500.00));
        testItem.setCost(BigDecimal.valueOf(300.00));
        testItem = itemRepository.save(testItem);

        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);
        stockRepository.save(stock);

        // Создаем партию для начального остатка (чтобы FEFO могла работать)
        com.warehouse.entity.Batch batch = new com.warehouse.entity.Batch();
        batch.setItem(testItem);
        batch.setWarehouse(defaultWarehouse());
        batch.setQuantity(10);
        batch.setExpiryDate(LocalDateTime.now().plusDays(365)); // Далекий срок годности
        batchRepository.save(batch);

        testItemId = testItem.getId();

        // Создаём пользователей только если их нет
        userRepository.findByUsername("admin").orElseGet(() -> {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("secret"));
            admin.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
            admin.setActive(true);
            return userRepository.save(admin);
        });

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(com.warehouse.entity.Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        adminToken = obtainToken("admin", "secret");
        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));
    }

    /**
     * ADMIN может зарегистрировать приход товара,
     * остаток на складе увеличивается на указанное количество.
     */
    @Test
    void adminTokenCanRegisterStockReceiptAndStockQuantityIncreases() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(testItemId))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.type").value("RECEIVE"))
                .andExpect(jsonPath("$.stockAfter").value(15));

        Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
        assertThat(updatedStock.getQuantity()).isEqualTo(15);
    }

    /**
     * USER токен не может зарегистрировать приход товара,
     * возвращает статус 403 Forbidden.
     */
    @Test
    void userTokenCannotRegisterStockReceiptReturns403() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена не может зарегистрировать приход товара,
     * возвращает статус 401 Unauthorized.
     */
    @Test
    void noTokenCannotRegisterStockReceiptReturns401() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Приход товара для несуществующего item_id возвращает статус 404 Not Found.
     */
    @Test
    void nonExistentItemReturns404() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                999L, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"));
    }

    /**
     * Приход товара для неактивного товара возвращает статус 404 Not Found.
     */
    @Test
    void inactiveItemReturns404() throws Exception {
        testItem.setActive(false);
        itemRepository.save(testItem);

        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"));
    }

    /**
     * Валидация: количество = 0 возвращает статус 400 Bad Request.
     */
    @Test
    void zeroQuantityValidationErrorReturns400() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, 0, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Валидация: отрицательное количество возвращает статус 400 Bad Request.
     */
    @Test
    void negativeQuantityValidationErrorReturns400() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(
                testItemId, -1, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
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

    /**
     * ADMIN может списать товар со склада,
     * остаток на складе уменьшается на указанное количество.
     */
    @Test
    void adminTokenCanWriteOffStockAndStockQuantityDecreases() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(testItemId))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.type").value("WRITE_OFF"))
                .andExpect(jsonPath("$.stockAfter").value(5));

        Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
        assertThat(updatedStock.getQuantity()).isEqualTo(5);
    }

    /**
     * USER токен не может списать товар,
     * возвращает статус 403 Forbidden.
     */
    @Test
    void userTokenCannotWriteOffStockReturns403() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена не может списать товар,
     * возвращает статус 401 Unauthorized.
     */
    @Test
    void noTokenCannotWriteOffStockReturns401() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Списание товара для несуществующего item_id возвращает статус 404 Not Found.
     */
    @Test
    void writeOffNonExistentItemReturns404() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(999L, 5);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"));
    }

    /**
     * Списание товара для неактивного товара возвращает статус 404 Not Found.
     */
    @Test
    void writeOffInactiveItemReturns404() throws Exception {
        testItem.setActive(false);
        itemRepository.save(testItem);

        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"));
    }

    /**
     * Списание товара при недостаточном остатке возвращает статус 422 Unprocessable Entity.
     */
    @Test
    void writeOffInsufficientStockReturns422() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 15);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));
    }

    /**
     * ADMIN проводит инвентаризацию: фактический остаток (7) меньше учётного (10).
     * Создаётся движение ADJUSTMENT на -3, остаток обновляется до 7.
     */
    @Test
    void adminStocktakeDecreasesStock() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7, null);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.quantity").value(-3))
                .andExpect(jsonPath("$.stockAfter").value(7));

        assertThat(stockRepository.findByItemId(testItemId).orElseThrow().getQuantity())
                .isEqualTo(7);
    }

    /**
     * USER токен не может проводить инвентаризацию,
     * возвращает статус 403 Forbidden.
     */

    @Test
    void userCannotStocktakeReturns403() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7, null);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена не может проводить инвентаризацию,
     * возвращает статус 401 Unauthorized.
     */
    @Test
    void noTokenCannotStocktakeReturns401() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7, null);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Валидация: количество = 0 возвращает статус 400 Bad Request.
     */
    @Test
    void writeOffZeroQuantityValidationErrorReturns400() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 0);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Валидация: отрицательное количество возвращает статус 400 Bad Request.
     */
    @Test
    void writeOffNegativeQuantityValidationErrorReturns400() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, -1);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Nested
    @DisplayName("Идемпотентность POST /api/movements/receive")
    class IdempotencyReceiveTests {

        private String idempotencyKey;

        @BeforeEach
        void setUp() {
            idempotencyKey = UUID.randomUUID().toString();
            idempotencyKeyRepository.deleteAll();
        }

        @Test
        @DisplayName("Первый запрос с ключом - создает движение и возвращает 200")
        void firstRequestWithKeyCreatesMovement() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

            MvcResult result = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemId").value(testItemId))
                    .andExpect(jsonPath("$.quantity").value(5))
                    .andExpect(jsonPath("$.type").value("RECEIVE"))
                    .andExpect(jsonPath("$.stockAfter").value(15))
                    .andReturn();

            // Проверяем, что ключ сохранился
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(1);

            // Проверяем остаток
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(15);
        }

        @Test
        @DisplayName("Повторный запрос с тем же ключом и телом - возвращает кешированный ответ, движение не создается")
        void duplicateRequestWithSameKeyReturnsCachedResponse() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

            // Первый запрос
            MvcResult firstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String firstResponse = firstResult.getResponse().getContentAsString();

            // Небольшая задержка, чтобы убедиться, что первый запрос полностью завершился
            Thread.sleep(100);

            // Второй запрос с тем же ключом
            MvcResult secondResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondResponse = secondResult.getResponse().getContentAsString();

            // Ответы должны быть идентичны
            assertThat(secondResponse).isEqualTo(firstResponse);

            // Проверяем, что остаток увеличился только на 5 (было 10 -> стало 15)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(15);

            // В БД только одна запись идемпотентного ключа
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Тот же ключ, но измененное тело - возвращает 409 Conflict")
        void sameKeyDifferentBodyReturnsConflict() throws Exception {
            // Первый запрос с quantity=5
            ReceiveStockRequest firstRequest = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

            mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstRequest)))
                    .andExpect(status().isOk());

            // Второй запрос с тем же ключом, но quantity=10
            ReceiveStockRequest secondRequest = new ReceiveStockRequest(testItemId,
                    10, LocalDateTime.now().plusDays(1));

            mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(secondRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

            // Проверяем, что остаток остался 15 (изменился только один раз)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(15);
        }

        @Test
        @DisplayName("Два разных ключа - создаются два разных движения")
        void differentKeysCreateDifferentMovements() throws Exception {
            String key1 = UUID.randomUUID().toString();
            String key2 = UUID.randomUUID().toString();

            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 3, LocalDateTime.now().plusDays(1));

            // Первый запрос с key1
            MvcResult firstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            // Второй запрос с key2
            MvcResult secondResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            // movementId должны быть разные
            String firstMovementId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                    .get("movementId").asText();
            String secondMovementId = objectMapper.readTree(secondResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            assertThat(firstMovementId).isNotEqualTo(secondMovementId);

            // Остаток увеличился на 6 (3+3)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(16);

            // В БД две записи идемпотентных ключей
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(2);
        }

        @Test
        @DisplayName("Запрос без Idempotency-Key (если ключ обязателен) - возвращает 400")
        void requestWithoutKeyReturnsBadRequest() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

            mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
        }

        @Test
        @DisplayName("Параллельные запросы с одинаковым ключом не создают дубль")
        void concurrentRequestsWithSameKeyDoNotCreateDuplicate() throws Exception {
            String key = UUID.randomUUID().toString();
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 3, LocalDateTime.now().plusDays(1));
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger processingCount = new AtomicInteger(0);

            String requestJson = objectMapper.writeValueAsString(request);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        mockMvc.perform(post("/api/movements/receive")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestJson))
                                .andExpect(status().isOk());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Может быть IdempotencyExecutionException (PROCESSING)
                        processingCount.incrementAndGet();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // Хотя бы один запрос должен быть успешным
            assertThat(successCount.get()).isGreaterThan(0);
            // Все запросы должны завершиться
            assertThat(successCount.get() + processingCount.get()).isEqualTo(threadCount);

            // Проверяем, что создано только одно движение
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(13); // 10 + 3

            // В БД только одна запись идемпотентного ключа
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Идемпотентность POST /api/movements/write-off")
    class IdempotencyWriteOffTests {

        private String idempotencyKey;

        @BeforeEach
        void setUp() {
            idempotencyKey = UUID.randomUUID().toString();
            idempotencyKeyRepository.deleteAll();
        }

        @Test
        @DisplayName("Первый запрос с ключом - создает движение списания")
        void firstWriteOffRequestWithKeyCreatesMovement() throws Exception {
            WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 3);

            mockMvc.perform(post("/api/movements/write-off")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemId").value(testItemId))
                    .andExpect(jsonPath("$.quantity").value(3))
                    .andExpect(jsonPath("$.type").value("WRITE_OFF"))
                    .andExpect(jsonPath("$.stockAfter").value(7));

            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("Повторный запрос с тем же ключом - возвращает кеш, остаток не меняется")
        void duplicateWriteOffRequestReturnsCachedResponse() throws Exception {
            WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 3);

            // Первый запрос
            MvcResult firstResult = mockMvc.perform(post("/api/movements/write-off")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String firstResponse = firstResult.getResponse().getContentAsString();

            // Небольшая задержка, чтобы убедиться, что первый запрос полностью завершился
            Thread.sleep(100);

            // Второй запрос
            MvcResult secondResult = mockMvc.perform(post("/api/movements/write-off")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondResponse = secondResult.getResponse().getContentAsString();

            // Ответы должны быть одинаковыми
            assertThat(secondResponse).isEqualTo(firstResponse);

            // Остаток изменился только на 3 (было 10 -> стало 7)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Кросс-контекстные сценарии (разные пользователи/эндпоинты)")
    class CrossContextTests {

        private String testIdempotencyKey;
        private String secondUserToken;

        @BeforeEach
        void setUp() throws Exception {
            testIdempotencyKey = UUID.randomUUID().toString();
            idempotencyKeyRepository.deleteAll();

            // Создаем второго пользователя
            User secondUser = userRepository.findByUsername("second_admin").orElseGet(() -> {
                User user = new User();
                user.setUsername("second_admin");
                user.setPassword(passwordEncoder.encode("secret"));
                user.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
                user.setActive(true);
                return userRepository.save(user);
            });

            secondUserToken = jwtUtil.generateToken(
                    secondUser.getUsername(),
                    secondUser.getId(),
                    List.of("ROLE_ADMIN")
            );
        }

        @Test
        @DisplayName("Один ключ для разных пользователей - создает разные движения")
        void sameKeyDifferentUsersCreateDifferentMovements() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 3, LocalDateTime.now().plusDays(1));

            // Первый пользователь с ключом
            MvcResult firstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String firstMovementId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            // Второй пользователь с тем же ключом
            MvcResult secondResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + secondUserToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondMovementId = objectMapper.readTree(secondResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            // Движения должны быть разные
            assertThat(firstMovementId).isNotEqualTo(secondMovementId);

            // В БД две записи (по одной на пользователя)
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(2);

            // Остаток увеличился на 6 (3+3)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(16);
        }

        @Test
        @DisplayName("Один ключ для разных эндпоинтов - создает разные движения")
        void sameKeyDifferentEndpointsCreateDifferentMovements() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 3, LocalDateTime.now().plusDays(1));

            // POST /receive с ключом
            MvcResult firstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String firstMovementId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            WriteOffStockRequest writeOffRequest = new WriteOffStockRequest(testItemId, 3);

            // POST /write-off с тем же ключом
            MvcResult secondResult = mockMvc.perform(post("/api/movements/write-off")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(writeOffRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondMovementId = objectMapper.readTree(secondResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            // Движения должны быть разные
            assertThat(firstMovementId).isNotEqualTo(secondMovementId);

            // В БД две записи (по одной на эндпоинт)
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(2);

            // Остаток: сначала +3, потом -3 → вернулся к 10
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("Чужой пользователь с тем же ключом - не получает чужой кеш")
        void differentUserWithSameKeyDoesNotGetCachedResponse() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

            // Первый пользователь создает движение с ключом
            MvcResult firstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String firstResponse = firstResult.getResponse().getContentAsString();
            String firstMovementId = objectMapper.readTree(firstResponse).get("movementId").asText();

            // Второй пользователь с тем же ключом — должен создать СВОЕ движение,
            // а не получить кеш первого
            MvcResult secondResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + secondUserToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondResponse = secondResult.getResponse().getContentAsString();
            String secondMovementId = objectMapper.readTree(secondResponse).get("movementId").asText();

            // Движения разные → значит второй не получил кеш первого
            assertThat(firstMovementId).isNotEqualTo(secondMovementId);

            // В БД две записи
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(2);

            // Остаток увеличился на 10 (5+5)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(20);
        }

        @Test
        @DisplayName("Повторный запрос от другого пользователя с тем же ключом - не возвращает кеш первого")
        void differentUserDuplicateRequestDoesNotReturnFirstUsersCache() throws Exception {
            ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 3, LocalDateTime.now().plusDays(1));

            // Первый пользователь создает движение
            mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // Второй пользователь с тем же ключом — создает свое движение
            MvcResult secondFirstResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + secondUserToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondFirstMovementId = objectMapper.readTree(secondFirstResult.getResponse().getContentAsString())
                    .get("movementId").asText();

            // Второй пользователь повторяет запрос с тем же ключом
            MvcResult secondDuplicateResult = mockMvc.perform(post("/api/movements/receive")
                            .header("Authorization", "Bearer " + secondUserToken)
                            .header("Idempotency-Key", testIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String secondDuplicateMovementId = objectMapper.readTree(secondDuplicateResult.getResponse()
                    .getContentAsString()).get("movementId").asText();

            // Повторный запрос второго пользователя вернул тот же movementId
            assertThat(secondDuplicateMovementId).isEqualTo(secondFirstMovementId);

            // В БД две записи (по одной на пользователя)
            long keyCount = idempotencyKeyRepository.count();
            assertThat(keyCount).isEqualTo(2);

            // Остаток увеличился на 6 (3+3)
            Stock updatedStock = stockRepository.findByItemId(testItemId).orElseThrow();
            assertThat(updatedStock.getQuantity()).isEqualTo(16);
        }
    }
}