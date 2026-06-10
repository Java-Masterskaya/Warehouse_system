package com.warehouse.service;

import com.warehouse.entity.Item;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ItemServiceCacheTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass testpass");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpass");
    }

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemRepository.deleteAll();

        Item item1 = createItem("SKU-001", "Ноутбук", "Электроника", true);
        Item item2 = createItem("SKU-002", "Стол", "Мебель", true);
        Item item3 = createItem("SKU-003", "Монитор", "Электроника", true);
        Item item4 = createItem("SKU-004", "Диван", "Мебель", false);

        itemRepository.saveAll(List.of(item1, item2, item3, item4));
    }

    @Test
    void getCategoriesShouldReturnDistinctActiveCategories() {
        List<String> categories = itemService.getCategories();

        assertThat(categories)
                .hasSize(2)
                .containsExactlyInAnyOrder("Электроника", "Мебель")
                .doesNotHaveDuplicates();
    }

    @Test
    void getCategoriesShouldBeCached() {
        List<String> firstCall = itemService.getCategories();

        itemRepository.deleteAll();

        List<String> secondCall = itemService.getCategories();

        assertThat(secondCall)
                .isEqualTo(firstCall)
                .hasSize(2);
    }

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