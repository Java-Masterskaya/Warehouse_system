package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.data.redis.host=invalid",
        "spring.data.redis.port=9999",
        "spring.data.redis.timeout=500ms",
        "resilience4j.circuitbreaker.instances.itemCache.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.instances.itemCache.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.itemCache.wait-duration-in-open-state=5s",
        "resilience4j.circuitbreaker.instances.itemCache.permitted-number-of-calls-in-half-open-state=2"
})
class ItemServiceRedisFallbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemService itemService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StockRepository stockRepository;

    private Long itemId;

    @BeforeEach
    void setUp() {
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();

        Category category = categoryRepository.save(
                Category.builder().name("Test").build()
        );

        Warehouse warehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse not configured"));

        Item item = Item.builder()
                .sku("SKU-FALLBACK")
                .name("Fallback Item")
                .category(category)
                .minStock(5)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .active(true)
                .build();

        item = itemRepository.save(item);
        itemId = item.getId();
    }

    @Test
    void shouldReturnDataWhenRedisUnavailable() {
        for (int i = 0; i < 7; i++) {
            ItemDetailsResponse response = itemService.getItem(itemId);
            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(itemId);
            assertThat(response.getName()).isEqualTo("Fallback Item");
        }
    }
}
