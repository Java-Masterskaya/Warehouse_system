package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки эндпоинта управления движениями товаров.
 * Тестирует API для регистрации прихода товара на склад.
 */
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
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private Item testItem;
    private Long testItemId;

    @BeforeEach
    void setUp() throws Exception {
        String uniqueSku = "SKU-MOV-" + System.currentTimeMillis();
        testItem = new Item();
        testItem.setSku(uniqueSku);
        testItem.setName("Тестовый товар");
        testItem.setCategory("Категория");
        testItem.setMinStock(5);
        testItem.setActive(true);
        testItem = itemRepository.save(testItem);

        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setQuantity(10);
        stockRepository.save(stock);

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
        
        userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(com.warehouse.entity.Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });
        
        adminToken = obtainToken("admin", "secret");
        userToken = jwtUtil.generateToken("testuser", List.of("ROLE_USER"));
    }

    /**
     * ADMIN может зарегистрировать приход товара,
     * остаток на складе увеличивается на указанное количество.
     */
    @Test
    void adminTokenCanRegisterStockReceiptAndStockQuantityIncreases() throws Exception {
        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
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
        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + userToken)
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
        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/receive")
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
        CreateStockMovementRequest request = new CreateStockMovementRequest(999L, 5);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
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

        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, 5);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
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
        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, 0);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
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
        CreateStockMovementRequest request = new CreateStockMovementRequest(testItemId, -1);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
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
        return objectMapper.readTree(response).get("token").asText();
    }
}
