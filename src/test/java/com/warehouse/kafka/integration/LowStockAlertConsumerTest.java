package com.warehouse.kafka.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.Item;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockAlert;
import com.warehouse.entity.StockMovement;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.OutboxEventRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
class LowStockAlertConsumerTest extends AbstractIntegrationTest {

    private static final String TEST_SKU = "SKU-001";
    private static final String TEST_ITEM_NAME = "Test Item";
    private static final String TEST_CATEGORY = "Test";
    private static final int TEST_MIN_STOCK = 10;
    private static final int TEST_CURRENT_STOCK = 5;
    private static final String TEST_TRIGGERED_BY = "admin";

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    StockAlertRepository stockAlertRepository;

    @Autowired
    StockRepository stockRepository;

    @Autowired
    StockMovementRepository stockMovementRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ItemRepository itemRepository;

    private Long testItemId;

    @BeforeEach
    void setUp() {
        // Очищаем данные в правильном порядке из-за FK constraint:
        // outbox -> stock_movements -> stock -> items; stock_alerts -> stock -> items
        // Используем deleteAllInBatch() для native SQL delete, что обходит кэш
        stockAlertRepository.deleteAllInBatch();
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
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
     * Проверяет, что повторная доставка одного и того же события не создает дубликаты.
     * Используется идемпотентный ключ: itemId + createdAt (из события).
     */
    @Test
    void shouldNotCreateDuplicateOnRepeatedDelivery() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LowStockAlertEvent event = new LowStockAlertEvent(
                testItemId, TEST_SKU, TEST_ITEM_NAME,
                TEST_CURRENT_STOCK, TEST_MIN_STOCK, TEST_TRIGGERED_BY, now
        );

        // Отправляем событие дважды (симуляция повторной доставки из Kafka)
        kafkaTemplate.send("low-stock-alerts", event.itemId().toString(), event);
        kafkaTemplate.send("low-stock-alerts", event.itemId().toString(), event);

        // Ждем, пока оба сообщения будут обработаны
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    // Должна быть только одна запись, несмотря на две отправки
                    assertThat(alerts).hasSize(1);

                    StockAlert alert = alerts.get(0);
                    assertThat(alert.getItem().getId()).isEqualTo(testItemId);
                    assertThat(alert.getCurrentStock()).isEqualTo(TEST_CURRENT_STOCK);
                    assertThat(alert.getMinStock()).isEqualTo(TEST_MIN_STOCK);
                    assertThat(alert.getTriggeredBy()).isEqualTo(TEST_TRIGGERED_BY);
                    // createdAt должен совпадать с событием (для идемпотентности)
                    assertThat(alert.getCreatedAt()).isEqualTo(now);
                });
    }

    /**
     * Проверяет, что события с разными createdAt создают разные алерты.
     * Это важно для правильной работы идемпотентности.
     *
     * Примечание: PostgreSQL TIMESTAMP хранит микросекунды (6 цифр),
     * поэтому truncating необходим для корректного сравнения.
     */
    @Test
    @DisplayName("Should create separate alert for different created_at values")
    void shouldCreateSeparateAlertForDifferentCreatedAt() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LowStockAlertEvent event1 = new LowStockAlertEvent(
                testItemId, TEST_SKU, TEST_ITEM_NAME,
                TEST_CURRENT_STOCK, TEST_MIN_STOCK, TEST_TRIGGERED_BY, now
        );

        // Ждем, пока первое событие будет обработано
        kafkaTemplate.send("low-stock-alerts", event1.itemId().toString(), event1);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(stockAlertRepository.findAll()).hasSize(1);
                });

        // Отправляем событие с другим createdAt (через 1 секунду)
        LocalDateTime later = now.plusSeconds(1);
        LowStockAlertEvent event2 = new LowStockAlertEvent(
                testItemId, TEST_SKU, TEST_ITEM_NAME,
                TEST_CURRENT_STOCK - 1, TEST_MIN_STOCK, TEST_TRIGGERED_BY, later
        );

        kafkaTemplate.send("low-stock-alerts", event2.itemId().toString(), event2);

        // Ждем, пока второе событие будет обработано
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    assertThat(alerts).hasSize(2);

                    // Проверяем, что оба алерта имеют разные created_at
                    assertThat(alerts.stream()
                            .map(StockAlert::getCreatedAt)
                            .sorted()
                            .toList())
                            .containsExactly(now, later);
                });

        // Проверяем, что в таблице две разные записи
        List<StockAlert> finalAlerts = stockAlertRepository.findAll();
        assertThat(finalAlerts).hasSize(2)
                .withFailMessage(() -> "Expected 2 alerts, but found " + finalAlerts.size());
    }
}
