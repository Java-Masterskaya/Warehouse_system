package com.warehouse.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что ответы {@code /api/movements/**} соответствуют зафиксированному
 * OpenAPI-контракту: успешные (200) и ошибочные (401/403/404/422) ответы должны совпадать
 * со схемами из {@code src/test/resources/openapi/warehouse.json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockMovementApiContractTest extends AbstractOpenApiContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Long testItemId;

    @BeforeEach
    void createItemWithStock() {
        Category category = categoryRepository.findByNameIgnoreCase("Контракт")
                .orElseGet(() -> categoryRepository.save(Category.builder().name("Контракт").build()));

        Item item = new Item();
        item.setSku("SKU-MOV-CONTRACT-" + System.currentTimeMillis());
        item.setName("Товар для контрактных тестов");
        item.setCategory(category);
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(500.00));
        item.setCost(BigDecimal.valueOf(300.00));
        item = itemRepository.save(item);
        testItemId = item.getId();

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);
        stockRepository.save(stock);

        Batch batch = new Batch();
        batch.setItem(item);
        batch.setWarehouse(defaultWarehouse());
        batch.setQuantity(10);
        batch.setExpiryDate(LocalDateTime.now().plusDays(365));
        batchRepository.save(batch);
    }

    @Test
    void registerReceiptSuccessMatchesContract() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }

    @Test
    void registerReceiptNonExistentItemMatchesContract() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(999999999L, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(openApiContract());
    }

    @Test
    void registerReceiptNoTokenMatchesContract() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(openApiResponseContract());
    }

    @Test
    void registerReceiptUserTokenMatchesContract() throws Exception {
        ReceiveStockRequest request = new ReceiveStockRequest(testItemId, 5, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/movements/receive")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(openApiContract());
    }

    @Test
    void writeOffInsufficientStockMatchesContract() throws Exception {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 999);

        mockMvc.perform(post("/api/movements/write-off")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(openApiContract());
    }

    @Test
    void getMovementHistoryMatchesContract() throws Exception {
        mockMvc.perform(get("/api/movements/" + testItemId + "/history")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }

    @Test
    void getMovementHistoryCursorMatchesContract() throws Exception {
        mockMvc.perform(get("/api/movements/" + testItemId + "/history")
                        .param("cursor", "")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }
}
