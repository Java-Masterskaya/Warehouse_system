package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки инвалидации кэша.
 */
@TestPropertySource(properties = "bucket4j.enabled=false")
@SpringBootTest
class CacheInvalidationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockReserveRepository reserveRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    private StockMovementService stockMovementService;
    @Autowired
    private StockAlertRepository stockAlertRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private Long itemId;

    @BeforeEach
    void setUp() {
        // Очищаем данные в правильном порядке из-за внешних ключей:
        // stock_alerts -> stock -> items
        stockAlertRepository.deleteAllInBatch();
        reserveRepository.deleteAll();
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();

        Category electronics = categoryRepository.save(
                Category.builder()
                        .name("Электроника")
                        .build()
        );

        Item item = new Item();
        item.setSku("SKU-001");
        item.setName("Ноутбук");
        item.setCategory(electronics);
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(1500.00));
        item.setCost(BigDecimal.valueOf(1000.00));
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);
        stockRepository.save(stock);

        itemId = item.getId();
    }

    /**
     * updateItem очищает кэш карточки товара.
     */
    @Test
    void updateItemShouldEvictItemCache() {
        itemService.getItem(itemId);

        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук Pro", "Электроника",
                10, BigDecimal.valueOf(1700.00), BigDecimal.valueOf(1100.00));
        itemService.updateItem(itemId, updateRequest);

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.getName()).isEqualTo("Ноутбук Pro");
        assertThat(response.getMinStock()).isEqualTo(10);
    }

    /**
     * softDeleteItem очищает кэш карточки товара.
     */
    @Test
    void softDeleteItemShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.isActive()).isTrue();

        itemService.softDeleteItem(itemId);

        try {
            itemService.getItem(itemId);
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("неактивен");
        }
    }

    /**
     * receiveMovement очищает кэш карточки товара.
     */
    @Test
    void receiveMovementShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.getCurrentStock()).isEqualTo(10);

        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 5);
        stockMovementService.registerReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.getCurrentStock()).isEqualTo(15);
    }

    /**
     * writeOffMovement очищает кэш карточки товара.
     */
    @Test
    void writeOffMovementShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.getCurrentStock()).isEqualTo(10);

        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 3);
        stockMovementService.writeOffReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.getCurrentStock()).isEqualTo(7);
    }

}
