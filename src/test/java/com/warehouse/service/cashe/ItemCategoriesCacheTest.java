package com.warehouse.service.cashe;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Item;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест кэширования категорий товаров.
 * Тестирует: получение списка категорий, кэширование, устойчивость к изменениям в БД.
 */
@ActiveProfiles("test")
class ItemCategoriesCacheTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemService itemService;

    @BeforeEach
    void setUp() {
        // Arrange: очищаем тестовые данные
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        // Arrange: создаем тестовые товары
        Item item1 = createItem("SKU-001", "Ноутбук", "Электроника", true);
        Item item2 = createItem("SKU-002", "Стол", "Мебель", true);
        Item item3 = createItem("SKU-003", "Монитор", "Электроника", true);
        Item item4 = createItem("SKU-004", "Диван", "Мебель", false);

        itemRepository.saveAll(List.of(item1, item2, item3, item4));
    }

    /**
     * Получение списка категорий должно возвращать только категории активных товаров без дубликатов.
     */
    @Test
    void getCategoriesShouldReturnDistinctActiveCategories() {
        // Act: получаем список категорий
        List<String> categories = itemService.getCategories();

        // Assert: проверяем, что категории корректны
        assertThat(categories)
                .hasSize(2)
                .containsExactlyInAnyOrder("Электроника", "Мебель")
                .doesNotHaveDuplicates();
    }

    /**
     * Получение списка категорий должно использовать кэш (данные не меняются после очистки БД).
     */
    @Test
    void getCategoriesShouldBeCached() {
        // Arrange: получаем список категорий и кэшируем
        List<String> firstCall = itemService.getCategories();

        // Act: очищаем БД (данные удалились)
        itemRepository.deleteAll();

        // Assert: при повторном запросе возвращаются закэшированные данные
        List<String> secondCall = itemService.getCategories();

        assertThat(secondCall)
                .isEqualTo(firstCall)
                .hasSize(2);
    }

    /**
     * Вспомогательный метод для создания тестового товара.
     *
     * @param sku      SKU товара
     * @param name     название товара
     * @param category категория товара
     * @param active   активность товара
     * @return созданный объект Item
     */
    private Item createItem(String sku, String name, String category, boolean active) {
        Item item = new Item();
        item.setSku(sku);
        item.setName(name);
        item.setCategory(category);
        item.setMinStock(0);
        item.setActive(active);
        return item;
    }
}
