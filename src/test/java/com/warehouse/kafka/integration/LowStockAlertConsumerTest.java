package com.warehouse.kafka.integration;

import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.Item;
import com.warehouse.entity.StockAlert;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Интеграционный тест для проверки потребления alertов о низких остатках из Kafka.
 */
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

    static final RedpandaContainer redpanda =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1"));

    static {
        redpanda.start();
    }

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

    @Autowired
    StockRepository stockRepository;

    private Long testItemId;

    @BeforeEach
    void setUp() {
        stockAlertRepository.deleteAll();
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        
        Item item = Item.builder()
                .sku(TEST_SKU)
                .name(TEST_ITEM_NAME)
                .category(TEST_CATEGORY)
                .minStock(TEST_MIN_STOCK)
                .active(true)
                .price(BigDecimal.valueOf(100.00))
                .cost(BigDecimal.valueOf(50.00))
                .build();
        item = itemRepository.save(item);
        testItemId = item.getId();
    }

    /**
     * Событие низкого остатка сохраняется в БД после получения из Kafka.
     */
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

    /**
     * Проверяет, что повторная доставка одного и того же сообщения из Kafka
     * не создает дубликат и не вызывает исключений.
     * 
     * Это критичный сценарий: при сбое consumer'а после commit offset'а,
     * Kafka может доставить сообщение повторно. Уникальный индекс и INSERT IGNORE
     * должны пропустить дубликат без DataIntegrityViolationException.
     */
    @Test
    void shouldSkipDuplicateOnRedelivery() {
        // Фиксированное время — важно, что одинаковое для обоих сообщений
        LocalDateTime triggeredAt = LocalDateTime.of(2026, 7, 11, 18, 30, 0);

        LowStockAlertEvent event = new LowStockAlertEvent(
                testItemId, TEST_SKU, TEST_ITEM_NAME,
                TEST_CURRENT_STOCK, TEST_MIN_STOCK, TEST_TRIGGERED_BY, triggeredAt
        );

        // Отправляем первое сообщение
        kafkaTemplate.send("low-stock-alerts", event.itemId().toString(), event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(stockAlertRepository.findAll()).hasSize(1));

        Long firstAlertId = stockAlertRepository.findAll().get(0).getId();

        // Симулируем повторную доставку — ТО ЖЕ САМОЕ сообщение
        kafkaTemplate.send("low-stock-alerts", event.itemId().toString(), event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    assertThat(alerts).hasSize(1);
                    assertThat(alerts.get(0).getId()).isEqualTo(firstAlertId);
                });
    }
}
