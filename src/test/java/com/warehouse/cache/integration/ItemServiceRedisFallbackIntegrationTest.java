package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.item.ItemService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext
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

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private CacheManager cacheManager;

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
                .sku("SKU-FALLBACK-ITEM")
                .name("Fallback Item")
                .category(category)
                .minStock(10)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .active(true)
                .build();

        item = itemRepository.save(item);
        itemId = item.getId();

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(warehouse);
        stock.setQuantity(20);
        stockRepository.save(stock);

        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("item")).thenReturn(cache);
        doThrow(new RuntimeException("Redis is down")).when(cache).get(any(), any(Class.class));
        doThrow(new RuntimeException("Redis is down")).when(cache).put(any(), any());
        doThrow(new RuntimeException("Redis is down")).when(cache).evict(any());
        doThrow(new RuntimeException("Redis is down")).when(cache).clear();
    }

    @Test
    void cacheShouldBeUnavailable() {
        Cache cache = cacheManager.getCache("item");
        assertThat(cache).isNotNull();

        assertThatThrownBy(() -> cache.get("anyKey", Object.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis is down");
    }

    @Test
    void shouldReturnItemWhenRedisUnavailable() {
        for (int i = 0; i < 7; i++) {
            ItemDetailsResponse item = itemService.getItem(itemId);
            assertThat(item).isNotNull();
            assertThat(item.getId()).isEqualTo(itemId);
            assertThat(item.getName()).isEqualTo("Fallback Item");
        }
    }

    @Test
    void shouldOpenCircuitBreakerAfterRepeatedFailures() {
        var breaker = circuitBreakerRegistry.circuitBreaker("itemCache");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 7; i++) {
            itemService.getItem(itemId);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}