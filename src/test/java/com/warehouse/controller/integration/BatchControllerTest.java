package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки функциональности партий товаров.
 * Тестирует создание партии при поступлении товара и хранение срока годности.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchControllerTest extends AbstractIntegrationTest {

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
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private Item testItem;
    private Long testItemId;
    private Warehouse testWarehouse;

    @BeforeEach
    void setUp() throws Exception {
        String uniqueSku = "SKU-BATCH-" + System.currentTimeMillis();
        Category category = categoryRepository.save(
                Category.builder()
                        .name("Категория " + uniqueSku)
                        .build()
        );

        testItem = new Item();
        testItem.setSku(uniqueSku);
        testItem.setName("Тестовый товар с партией");
        testItem.setCategory(category);
        testItem.setMinStock(5);
        testItem.setActive(true);
        testItem.setPrice(BigDecimal.valueOf(500.00));
        testItem.setCost(BigDecimal.valueOf(300.00));
        testItem.setBarcode("ITEM-TEST-BATCH-" + uniqueSku);
        testItem = itemRepository.save(testItem);

        testWarehouse = defaultWarehouse();

        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setWarehouse(testWarehouse);
        stock.setQuantity(10);
        stockRepository.save(stock);

        Batch initialBatch = new Batch();
        initialBatch.setItem(testItem);
        initialBatch.setWarehouse(testWarehouse);
        initialBatch.setQuantity(10);
        initialBatch.setExpiryDate(LocalDateTime.now().plusDays(365));
        batchRepository.save(initialBatch);

        testItemId = testItem.getId();

        // Создаём пользователя admin только если его нет
        userRepository.findByUsername("admin").orElseGet(() -> {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("secret"));
            admin.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
            admin.setActive(true);
            return userRepository.save(admin);
        });

        adminToken = obtainToken("admin", "secret");
    }

    /**
     * Поступление товара создаёт партию с expiry_date.
     * Проверяет, что партия сохраняется в базу данных и ответ содержит expiryDate.
     */
    @Test
    void receiptCreatesBatchWithExpiryDate() throws Exception {
        LocalDateTime expiryDate = LocalDateTime.now().plusDays(30);
        ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, expiryDate);

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

        // Проверяем, что партия создана в базе данных
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                testItemId, testWarehouse.getId());
        assertThat(batches).hasSize(2);
        Batch batch = batches.get(0);
        assertThat(batch.getItem().getId()).isEqualTo(testItemId);
        assertThat(batch.getWarehouse().getId()).isEqualTo(testWarehouse.getId());
        assertThat(batch.getQuantity()).isEqualTo(5);
        assertThat(batch.getExpiryDate()).isBetween(expiryDate.minusSeconds(1), expiryDate.plusSeconds(1));
        assertThat(batch.getId()).isNotNull();

        // Проверяем, что stock_movement ссылается на партию
        List<StockMovement> receiveMovements = stockMovementRepository.findAll().stream()
                .filter(movement -> movement.getItem().getId().equals(testItemId))
                .filter(movement -> movement.getType() == MovementType.RECEIVE)
                .toList();
        assertThat(receiveMovements).singleElement().satisfies(movement -> {
            assertThat(movement.getBatch()).isNotNull();
            assertThat(movement.getBatch().getId()).isEqualTo(batch.getId());
            assertThat(movement.getWarehouse().getId()).isEqualTo(testWarehouse.getId());
        });

        Stock updatedStock = stockRepository.findByItemIdAndWarehouseId(
                testItemId, testWarehouse.getId()).orElseThrow();
        assertThat(updatedStock.getQuantity()).isEqualTo(15);
        assertThat(updatedStock.getQuantity())
                .isEqualTo(batches.stream().mapToInt(Batch::getQuantity).sum());
    }

    /**
     * Поступление одного товара дважды создаёт две партии с разными expiry_date.
     */
    @Test
    void receiptTwiceCreatesTwoBatches() throws Exception {
        LocalDateTime expiryDate1 = LocalDateTime.now().plusDays(30);
        ReceiveStockRequest request1 = new ReceiveStockRequest(testItemId, 5, expiryDate1);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        LocalDateTime expiryDate2 = LocalDateTime.now().plusDays(60);
        ReceiveStockRequest request2 = new ReceiveStockRequest(testItemId, 3, expiryDate2);

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isOk());

        // Проверяем, что создано две партии
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                testItemId, testWarehouse.getId());
        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(batch ->
                assertThat(batch.getWarehouse().getId()).isEqualTo(testWarehouse.getId()));

        Batch batch1 = batches.get(0);
        assertThat(batch1.getExpiryDate()).isBetween(expiryDate1.minusSeconds(1), expiryDate1.plusSeconds(1));
        assertThat(batch1.getQuantity()).isEqualTo(5);

        Batch batch2 = batches.get(1);
        assertThat(batch2.getExpiryDate()).isBetween(expiryDate2.minusSeconds(1), expiryDate2.plusSeconds(1));
        assertThat(batch2.getQuantity()).isEqualTo(3);

        Stock updatedStock = stockRepository.findByItemIdAndWarehouseId(
                testItemId, testWarehouse.getId()).orElseThrow();
        assertThat(updatedStock.getQuantity()).isEqualTo(18);
        assertThat(updatedStock.getQuantity())
                .isEqualTo(batches.stream().mapToInt(Batch::getQuantity).sum());
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
