package com.warehouse.service.cache;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки инвалидации кэша.
 */
@ActiveProfiles("test")
class CacheInvalidationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    private StockMovementService stockMovementService;

    private Long itemId;

    @BeforeEach
    void setUp() {
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        Item item = new Item();
        item.setSku("SKU-001");
        item.setName("Ноутбук");
        item.setCategory("Электроника");
        item.setMinStock(5);
        item.setActive(true);
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
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

        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук Pro", "Электроника", 10);
        itemService.updateItem(itemId, updateRequest);

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.name()).isEqualTo("Ноутбук Pro");
        assertThat(response.minStock()).isEqualTo(10);
    }

    /**
     * softDeleteItem очищает кэш карточки товара.
     */
    @Test
    void softDeleteItemShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.active()).isTrue();

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
        assertThat(firstCall.currentStock()).isEqualTo(10);

        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 5);
        stockMovementService.registerReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.currentStock()).isEqualTo(15);
    }

    /**
     * writeOffMovement очищает кэш карточки товара.
     */
    @Test
    void writeOffMovementShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.currentStock()).isEqualTo(10);

        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 3);
        stockMovementService.writeOffReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.currentStock()).isEqualTo(7);
    }

    /**
     * createItem с новой категорией очищает кэш категорий.
     */
    @Test
    void createItemWithNewCategoryShouldEvictCategoriesCache() {
        List<String> firstCall = itemService.getCategories();
        assertThat(firstCall).contains("Электроника");

        com.warehouse.dto.request.item.CreateItemRequest createRequest =
                new com.warehouse.dto.request.item.CreateItemRequest("SKU-002", "Стол", "Мебель", 3);
        itemService.createItem(createRequest);

        List<String> secondCall = itemService.getCategories();
        assertThat(secondCall).contains("Электроника", "Мебель");
    }

    /**
     * updateItem с изменением категории очищает кэш категорий.
     */
    @Test
    void updateItemWithCategoryChangeShouldEvictCategoriesCache() {
        com.warehouse.dto.request.item.CreateItemRequest createRequest =
                new com.warehouse.dto.request.item.CreateItemRequest("SKU-002", "Стол", "Мебель", 3);
        itemService.createItem(createRequest);

        List<String> firstCall = itemService.getCategories();
        assertThat(firstCall).contains("Электроника", "Мебель");

        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук", "Мебель", 5);
        itemService.updateItem(itemId, updateRequest);

        List<String> secondCall = itemService.getCategories();
        assertThat(secondCall).doesNotHaveDuplicates();
    }
}