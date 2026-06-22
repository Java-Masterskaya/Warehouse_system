package com.warehouse.kafka.integration;

import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.Item;
import com.warehouse.entity.StockAlert;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = WarehouseApp.class)
class LowStockAlertConsumerTest {

    private static final String TEST_SKU = "SKU-001";
    private static final String TEST_ITEM_NAME = "Test Item";
    private static final String TEST_CATEGORY = "Test";
    private static final int TEST_MIN_STOCK = 10;
    private static final int TEST_CURRENT_STOCK = 5;
    private static final String TEST_TRIGGERED_BY = "admin";

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1")
    );

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    StockAlertRepository stockAlertRepository;

    @Autowired
    ItemRepository itemRepository;

    private Long testItemId;

    @BeforeEach
    void setUp() {
        stockAlertRepository.deleteAll();
        Item item = Item.builder()
                .sku(TEST_SKU)
                .name(TEST_ITEM_NAME)
                .category(TEST_CATEGORY)
                .minStock(TEST_MIN_STOCK)
                .active(true)
                .build();
        item = itemRepository.save(item);
        testItemId = item.getId();
    }

    @Test
    void shouldSaveAlertOnMessage() {
        LowStockAlertEvent event = new LowStockAlertEvent(
                testItemId, TEST_SKU, TEST_ITEM_NAME,
                TEST_CURRENT_STOCK, TEST_MIN_STOCK, TEST_TRIGGERED_BY, LocalDateTime.now()
        );

        kafkaTemplate.send("low-stock-alerts", event.itemId().toString(), event);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    Optional<StockAlert> saved = stockAlertRepository.findAll().stream().findFirst();
                    assertThat(saved).isPresent();
                    StockAlert alert = saved.get();
                    assertThat(alert.getItem().getId()).isEqualTo(testItemId);
                    assertThat(alert.getCurrentStock()).isEqualTo(TEST_CURRENT_STOCK);
                    assertThat(alert.getMinStock()).isEqualTo(TEST_MIN_STOCK);
                    assertThat(alert.getTriggeredBy()).isEqualTo(TEST_TRIGGERED_BY);
                });
    }
}
