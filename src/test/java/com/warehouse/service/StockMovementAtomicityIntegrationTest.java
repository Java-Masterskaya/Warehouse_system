package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.OutboxEventRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-тест для проверки атомарности сохранения движения товара и outbox события.
 * Проверяет, что при падении транзакции ни движение, ни событие не остаются в БД.
 */
@SpringBootTest
class StockMovementAtomicityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CategoryRepository categoryRepository;

    private Item testItem;
    private Long testItemId;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Очищаем outbox перед каждым тестом
        outboxEventRepository.deleteAll();

        Category category = categoryRepository.findByNameIgnoreCase("Категория")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Категория")
                                .build()
                ));

        // Создаём тестовый товар
        testItem = new Item();
        testItem.setSku("SKU-ATOM-" + System.currentTimeMillis());
        testItem.setName("Тестовый товар для атомарности");
        testItem.setCategory(category);
        testItem.setMinStock(10);
        testItem.setActive(true);
        testItem = itemRepository.save(testItem);

        // Создаём остаток
        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(20);
        stockRepository.save(stock);

        testItemId = testItem.getId();

        // Создаём пользователя
        testUser = new User();
        testUser.setUsername("atomic-test-" + System.currentTimeMillis());
        testUser.setPassword("password");
        testUser.setRole(com.warehouse.entity.Role.ROLE_USER);
        testUser.setActive(true);
        testUser = userRepository.save(testUser);
    }

    /**
     * Проверяет, что при успешной транзакции сохраняются и движение, и outbox событие.
     */
    @Test
    void bothMovementAndOutboxSavedOnSuccessfulTransaction() {
        // Arrange - списываем 16, чтобы остаток стал 4 (меньше minStock=10)
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(testItemId, 16);
        UserContext userContext = new UserContext(testUser.getId(), testUser.getUsername());

        // Act
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        
        StockMovementResponse response = txTemplate.execute(status -> {
            try {
                return stockMovementService.writeOffReceipt(request, userContext);
            } catch (Exception e) {
                status.setRollbackOnly();
                throw e;
            }
        });

        // Assert
        assertTrue(response.lowStockAlert());

        // Проверяем, что движение сохранено
        assertThat(stockRepository.findByItemId(testItemId).orElseThrow().getQuantity())
                .isEqualTo(4);

        // Проверяем, что outbox событие сохранено
        List<OutboxEvent> outboxEvents = outboxEventRepository.findPendingEvents(10);
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("LowStockAlert");
    }

    /**
     * Проверяет, что при rollback сохранение движения и outbox события откатываются.
     * Симулирует ошибку после сохранения движения, но до коммита.
     */
    @Test
    void bothMovementAndOutboxRollbackOnError() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(testItemId, 5);
        UserContext userContext = new UserContext(testUser.getId(), testUser.getUsername());

        // Подсчитываем начальное количество
        long initialMovementCount = stockMovementRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        // Act - запускаем транзакцию и вызываем ошибку
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Exception thrown = null;
        try {
            txTemplate.execute(status -> {
                try {
                    StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);
                    // Симулируем ошибку после сохранения движения, но до коммита
                    throw new RuntimeException("Simulated error after movement save");
                } catch (Exception e) {
                    status.setRollbackOnly();
                    throw e;
                }
            });
        } catch (Exception e) {
            thrown = e;
        }

        // Assert
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).isEqualTo("Simulated error after movement save");

        // Проверяем, что НИ движение, НИ outbox событие не сохранились
        assertThat(stockMovementRepository.count()).isEqualTo(initialMovementCount);
        assertThat(outboxEventRepository.count()).isEqualTo(initialOutboxCount);
    }

    /**
     * Проверяет, что при ошибке валидации (InsufficientStockException)
     * движение не сохраняется и outbox событие тоже не создаётся.
     */
    @Test
    void neitherMovementNorOutboxSavedOnValidationFailure() {
        // Arrange - списываем больше, чем есть на складе
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(testItemId, 100);
        UserContext userContext = new UserContext(testUser.getId(), testUser.getUsername());

        // Подсчитываем начальное количество
        long initialMovementCount = stockMovementRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        // Act
        Exception thrown = null;
        try {
            stockMovementService.writeOffReceipt(request, userContext);
        } catch (Exception e) {
            thrown = e;
        }

        // Assert
        assertThat(thrown).isNotNull();
        assertThat(thrown.getClass().getName()).contains("InsufficientStockException");

        // Проверяем, что НИ движение, НИ outbox событие не сохранились
        assertThat(stockMovementRepository.count()).isEqualTo(initialMovementCount);
        assertThat(outboxEventRepository.count()).isEqualTo(initialOutboxCount);
    }

    /**
     * Инвентаризация: проверяет атомарность при создании ADJUSTMENT и outbox события.
     */
    @Test
    void stocktakeAtomicityTest() {
        // Arrange - устанавливаем количество меньше minStock
        Stock stock = stockRepository.findByItemId(testItemId).orElseThrow();
        stock.setQuantity(20); // устанавливаем больше minStock, чтобы stocktake привёл к ADJUSTMENT
        stockRepository.save(stock);

        long initialMovementCount = stockMovementRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        // Act - проводим инвентаризацию с меньшим количеством (создаст ADJUSTMENT на -15)
        StocktakeRequest request = new StocktakeRequest(testItemId, 5);
        UserContext userContext = new UserContext(testUser.getId(), testUser.getUsername());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        StockMovementResponse response = txTemplate.execute(status -> {
            try {
                return stockMovementService.stocktake(request, userContext);
            } catch (Exception e) {
                status.setRollbackOnly();
                throw e;
            }
        });

        // Assert
        assertThat(response.lowStockAlert()).isTrue();
        assertThat(response.type().name()).isEqualTo("ADJUSTMENT");

        // Проверяем, что движение сохранено
        assertThat(stockMovementRepository.count()).isEqualTo(initialMovementCount + 1);

        // Проверяем, что outbox событие сохранено
        List<OutboxEvent> outboxEvents = outboxEventRepository.findPendingEvents(10);
        assertThat(outboxEvents).hasSize(1)
                .withFailMessage(() -> "Expected 1 PENDING event, found: "
                        + outboxEventRepository.count() + " total events");
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("LowStockAlert");
    }
}
