package com.warehouse.kafka.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.StockAlert;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.OutboxEventRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Интеграционный тест для проверки потребления alertов о низких остатках из Kafka.
 */
@Tag("integration")
@TestPropertySource(properties = "bucket4j.enabled=false")
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
    StockMovementRepository stockMovementRepository;

    @Autowired
    BatchRepository batchRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    StockReserveRepository stockReserveRepository;

    @Autowired
    PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    StockRepository stockRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    private Long testItemId;

    @BeforeEach
    void setUp() {
        // Порядок важен: сначала зависимости (FK), потом родительские таблицы
        outboxEventRepository.deleteAll();
        stockAlertRepository.deleteAll();
        stockMovementRepository.deleteAll();
        stockReserveRepository.deleteAll();
        purchaseOrderItemRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        batchRepository.deleteAll();
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();

        Category category = categoryRepository.save(
                Category.builder()
                        .name(TEST_CATEGORY)
                        .build()
        );

        Item item = Item.builder()
                .sku(TEST_SKU)
                .name(TEST_ITEM_NAME)
                .category(category)
                .minStock(TEST_MIN_STOCK)
                .active(true)
                .price(BigDecimal.valueOf(100.00))
                .cost(BigDecimal.valueOf(50.00))
                .barcode("ITEM-TEST-LOWSTOCK-001")
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
                    List<StockAlert> alerts = stockAlertRepository.findByItemId(testItemId);
                    assertThat(alerts).isNotEmpty();
                    StockAlert alert = alerts.get(0);
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
