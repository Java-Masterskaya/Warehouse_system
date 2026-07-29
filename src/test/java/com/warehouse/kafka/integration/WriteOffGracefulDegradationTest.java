package com.warehouse.kafka.integration;

import com.warehouse.WarehouseApp;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.*;
import com.warehouse.repository.*;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.messaging.Message;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = WarehouseApp.class, properties = {
    "spring.kafka.outbox.polling.interval-ms=3600000"
})
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

    private Item testItem;
    private User testUser;
    private Long testItemId;
    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
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
        stock.setQuantity(20);
        stockRepository.save(stock);

        Batch batch = new Batch();
        batch.setItem(testItem);
        batch.setWarehouse(defaultWarehouse);
        batch.setQuantity(20);
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
    void shouldWriteOffSuccessfullyWhenKafkaUnavailable() {
        WriteOffStockRequest request = new WriteOffStockRequest(testItemId, 15);
        UserContext ctx = new UserContext(testUser.getId(), testUser.getUsername());

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, ctx);

        assertThat(response.lowStockAlert()).isTrue();
        assertThat(response.type()).isEqualTo(MovementType.WRITE_OFF);
        assertThat(response.quantity()).isEqualTo(15);

        Stock stock = stockRepository.findByItemId(testItemId).orElseThrow();
        assertThat(stock.getQuantity()).isEqualTo(5);

        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(10);
        assertThat(pending).hasSize(1);
        OutboxEvent event = pending.get(0);
        assertThat(event.getEventType()).isEqualTo("LowStockAlert");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @TestConfiguration
    static class KafkaUnavailableConfig {
        @Bean
        @Primary
        public KafkaTemplate<String, Object> stubKafkaTemplate() {
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
            when(template.send(any(Message.class)))
                    .thenThrow(new RuntimeException("Kafka is down"));
            when(template.send(any(String.class), any(), any()))
                    .thenThrow(new RuntimeException("Kafka is down"));
            when(template.send(any(String.class), any()))
                    .thenThrow(new RuntimeException("Kafka is down"));
            return template;
        }
    }
}