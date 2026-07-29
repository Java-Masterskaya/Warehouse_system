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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private RedisConnectionFactory redisConnectionFactory;

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
    }

    @Test
    void redisShouldBeUnavailable() {
        assertThatThrownBy(() -> redisConnectionFactory.getConnection())
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

    @TestConfiguration
    static class RedisUnavailableConfig {

        @Bean
        @Primary
        public RedisConnectionFactory stubRedisConnectionFactory() {
            return new RedisConnectionFactory() {
                @Override
                public RedisConnection getConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public RedisClusterConnection getClusterConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public RedisSentinelConnection getSentinelConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public boolean getConvertPipelineAndTxResults() {
                    return false;
                }

                @Override
                public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
                    return null;
                }
            };
        }
    }
}