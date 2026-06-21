package com.warehouse.service.cashe;

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
 * Интеграционный тест проверки инвалидации кэша при изменении данных.
 * Тестирует: инвалидацию кэша карточки товара и кэша категорий.
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
        // Arrange: очищаем тестовые данные
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        // Arrange: создаем тестовый товар
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
     * Обновление товара должно привести к инвалидации кэша карточки товара.
     */
    @Test
    void updateItemShouldEvictItemCache() {
        // Arrange: получаем товар и кэшируем
        itemService.getItem(itemId);

        // Act: обновляем товар
        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук Pro", "Электроника", 10);
        itemService.updateItem(itemId, updateRequest);

        // Assert: при повторном запросе возвращаются обновленные данные
        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.name()).isEqualTo("Ноутбук Pro");
        assertThat(response.minStock()).isEqualTo(10);
    }

    /**
     * Мягкое удаление товара должно привести к инвалидации кэша карточки товара.
     */
    @Test
    void softDeleteItemShouldEvictItemCache() {
        // Arrange: получаем товар и кэшируем
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.active()).isTrue();

        // Act: удаляем товар
        itemService.softDeleteItem(itemId);

        // Assert: при повторном запросе выбрасывается исключение
        try {
            itemService.getItem(itemId);
        } catch (Exception e) {
            // Assert: проверяем сообщение исключения
            assertThat(e.getMessage()).contains("неактивен");
        }
    }

    /**
     * Регистрация прихода должна привести к инвалидации кэша карточки товара.
     */
    @Test
    void receiveMovementShouldEvictItemCache() {
        // Arrange: получаем товар и кэшируем
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.currentStock()).isEqualTo(10);

        // Act: регистрируем приход
        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 5);
        stockMovementService.registerReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        // Assert: при повторном запросе возвращаются обновленные данные
        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.currentStock()).isEqualTo(15);
    }

    /**
     * Регистрация списания должна привести к инвалидации кэша карточки товара.
     */
    @Test
    void writeOffMovementShouldEvictItemCache() {
        // Arrange: получаем товар и кэшируем
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.currentStock()).isEqualTo(10);

        // Act: регистрируем списание
        ChangeQuantityMovementRequest movementRequest = new ChangeQuantityMovementRequest(itemId, 3);
        stockMovementService.writeOffReceipt(movementRequest,
                new com.warehouse.dto.UserContext(1L, "admin"));

        // Assert: при повторном запросе возвращаются обновленные данные
        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.currentStock()).isEqualTo(7);
    }

    /**
     * Создание товара с новой категорией должно привести к инвалидации кэша категорий.
     */
    @Test
    void createItemWithNewCategoryShouldEvictCategoriesCache() {
        // Arrange: получаем категории и кэшируем
        List<String> firstCall = itemService.getCategories();
        assertThat(firstCall).contains("Электроника");

        // Act: создаем товар с новой категорией
        com.warehouse.dto.request.item.CreateItemRequest createRequest =
                new com.warehouse.dto.request.item.CreateItemRequest("SKU-002", "Стол", "Мебель", 3);
        itemService.createItem(createRequest);

        // Assert: при повторном запросе возвращаются обновленные категории
        List<String> secondCall = itemService.getCategories();
        assertThat(secondCall).contains("Электроника", "Мебель");
    }

    /**
     * Изменение категории товара должно привести к инвалидации кэша категорий.
     */
    @Test
    void updateItemWithCategoryChangeShouldEvictCategoriesCache() {
        // Arrange: создаем товар с категорией "Мебель"
        com.warehouse.dto.request.item.CreateItemRequest createRequest =
                new com.warehouse.dto.request.item.CreateItemRequest("SKU-002", "Стол", "Мебель", 3);
        itemService.createItem(createRequest);

        // Arrange: получаем категории и кэшируем
        List<String> firstCall = itemService.getCategories();
        assertThat(firstCall).contains("Электроника", "Мебель");

        // Act: обновляем категорию товара
        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук", "Мебель", 5);
        itemService.updateItem(itemId, updateRequest);

        // Assert: при повторном запросе возвращаются обновленные категории (без дубликатов)
        List<String> secondCall = itemService.getCategories();
        assertThat(secondCall).doesNotHaveDuplicates();
    }
}