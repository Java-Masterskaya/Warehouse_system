package com.warehouse.kafka.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.kafka.outbox.OutboxEventRelay;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.OutboxEventRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.movement.StockMovementService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = WarehouseApp.class)
@DirtiesContext
class WriteOffGracefulDegradationTest extends AbstractIntegrationTest {

    @Autowired
    private StockMovementService stockMovementService;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private WarehouseRepository warehouseRepository;
    @Autowired
    private BatchRepository batchRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private StockReserveRepository stockReserveRepository;
    @Autowired
    private StockAlertRepository stockAlertRepository;
    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private OutboxEventRelay outboxEventRelay;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private Item testItem;
    private User testUser;
    private Long testItemId;
    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka is down")));

        stockMovementRepository.deleteAllInBatch();
        stockReserveRepository.deleteAllInBatch();
        batchRepository.deleteAll();
        stockAlertRepository.deleteAll();
        purchaseOrderItemRepository.deleteAllInBatch();
        purchaseOrderRepository.deleteAllInBatch();
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        outboxEventRepository.deleteAll();

        Category category = categoryRepository.save(Category.builder().name("Test").build());

        defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse not configured"));

        testItem = new Item();
        testItem.setSku("SKU-GRACE-" + System.currentTimeMillis());
        testItem.setName("Graceful Item");
        testItem.setCategory(category);
        testItem.setMinStock(10);
        testItem.setActive(true);
        testItem.setPrice(BigDecimal.TEN);
        testItem.setCost(BigDecimal.ONE);
        testItem = itemRepository.save(testItem);

        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setWarehouse(defaultWarehouse);
        stock.setQuantity(10);
        stockRepository.save(stock);

        Batch batch = new Batch();
        batch.setItem(testItem);
        batch.setWarehouse(defaultWarehouse);
        batch.setQuantity(10);
        batch.setExpiryDate(LocalDateTime.now().plusDays(30));
        batchRepository.save(batch);

        testItemId = testItem.getId();

        testUser = userRepository.findByUsername("admin")
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername("admin");
                    user.setPassword("encoded");
                    user.setRole(Role.ROLE_ADMIN);
                    user.setActive(true);
                    return userRepository.save(user);
                });
    }

    @Test
    void shouldSaveOutboxEventsAndOpenCircuitBreakerWhenKafkaIsDown() {
        UserContext ctx = new UserContext(testUser.getId(), testUser.getUsername());

        for (int i = 0; i < 7; i++) {
            WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 1);
            StockMovementResponse response = stockMovementService.writeOffReceipt(request, ctx);
            assertThat(response.lowStockAlert()).isTrue();
        }

        List<OutboxEvent> pendingEvents = outboxEventRepository.findAll();
        assertThat(pendingEvents).hasSize(7);
        pendingEvents.forEach(event -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING));

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("kafkaProducer");
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN)
            );

        outboxEventRelay.relayPendingEvents();

        List<OutboxEvent> updatedEvents = outboxEventRepository.findAll();
        assertThat(updatedEvents).hasSize(7);
        updatedEvents.forEach(event ->
                assertThat(event.getStatus()).isIn(OutboxStatus.FAILED, OutboxStatus.PENDING)
        );
    }
}