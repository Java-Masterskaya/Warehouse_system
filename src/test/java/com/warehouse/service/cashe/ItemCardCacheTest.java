package com.warehouse.service.cashe;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест кэширования карточки товара.
 * Тестирует: получение данных о товаре, кэширование, устойчивость к изменениям в БД.
 */
@ActiveProfiles("test")
class ItemCardCacheTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemService itemService;

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
     * Получение карточки товара должно возвращать корректные данные.
     */
    @Test
    void getItemShouldReturnItemDetails() {
        // Act: получаем карточку товара
        ItemDetailsResponse response = itemService.getItem(itemId);

        // Assert: проверяем, что данные корректны
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(itemId);
        assertThat(response.name()).isEqualTo("Ноутбук");
        assertThat(response.currentStock()).isEqualTo(10);
    }

    /**
     * Получение карточки товара должно использовать кэш (данные не меняются после очистки БД).
     */
    @Test
    void getItemShouldBeCached() {
        // Arrange: получаем карточку товара и кэшируем
        ItemDetailsResponse firstCall = itemService.getItem(itemId);

        // Act: очищаем БД (данные удалились)
        stockRepository.deleteAll();
        itemRepository.deleteAll();

        // Assert: при повторном запросе возвращаются закэшированные данные
        ItemDetailsResponse secondCall = itemService.getItem(itemId);

        assertThat(secondCall).isEqualTo(firstCall);
        assertThat(secondCall.name()).isEqualTo("Ноутбук");
        assertThat(secondCall.currentStock()).isEqualTo(10);
    }
}
