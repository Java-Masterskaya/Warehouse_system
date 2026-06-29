package com.warehouse.service.cache;

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
 * Интеграционный тест для проверки кэширования категорий товаров.
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
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        Item item1 = createItem("SKU-001", "Ноутбук", "Электроника", true);
        Item item2 = createItem("SKU-002", "Стол", "Мебель", true);
        Item item3 = createItem("SKU-003", "Монитор", "Электроника", true);
        Item item4 = createItem("SKU-004", "Диван", "Мебель", false);

        itemRepository.saveAll(List.of(item1, item2, item3, item4));
    }

    /**
     * getCategories возвращает список distinct активных категорий.
     */
    @Test
    void getCategoriesShouldReturnDistinctActiveCategories() {
        List<String> categories = itemService.getCategories();

        assertThat(categories)
                .hasSize(2)
                .containsExactlyInAnyOrder("Электроника", "Мебель")
                .doesNotHaveDuplicates();
    }

    /**
     * getCategories возвращает данные из кэша даже после удаления из БД.
     */
    @Test
    void getCategoriesShouldBeCached() {
        List<String> firstCall = itemService.getCategories();

        itemRepository.deleteAll();

        List<String> secondCall = itemService.getCategories();

        assertThat(secondCall)
                .isEqualTo(firstCall)
                .hasSize(2);
    }

    /**
     * Вспомогательный метод для создания тестового товара.
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