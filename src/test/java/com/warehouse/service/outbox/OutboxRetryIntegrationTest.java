package com.warehouse.service.outbox;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.kafka.outbox.OutboxEventRelay;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.OutboxEventRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration-тесты для проверки лимита ретраев и экспоненциального бэкоффа.
 * Проверяет, что битый payload не бесконечно пытается отправиться, а переходит в FAILED статус.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@DisplayName("Outbox retry limit and backoff integration tests")
class OutboxRetryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private OutboxEventRelay outboxEventRelay;

    private Item testItem;
    private Long testItemId;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Очищаем outbox перед каждым тестом
        outboxEventRepository.deleteAll();

        // Создаём тестовый товар
        testItem = new Item();
        testItem.setSku("SKU-RETRY-" + System.currentTimeMillis());
        testItem.setName("Тестовый товар для ретраев");
        testItem.setCategory("Категория");
        testItem.setMinStock(10);
        testItem.setActive(true);
        testItem = itemRepository.save(testItem);

        // Создаём остаток
        Stock stock = new Stock();
        stock.setItem(testItem);
        stock.setQuantity(20);
        stockRepository.save(stock);

        testItemId = testItem.getId();

        // Создаём пользователя
        testUser = new User();
        testUser.setUsername("retry-test-" + System.currentTimeMillis());
        testUser.setPassword("password");
        testUser.setRole(com.warehouse.entity.Role.ROLE_USER);
        testUser.setActive(true);
        testUser = userRepository.save(testUser);
    }

    /**
     * Проверяет, что после 3 неудачных попыток событие переходит в FAILED статус.
     * Симулируем битый payload, который не удастся десериализовать.
     */
    @Test
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
    @DisplayName("Should mark event as FAILED after max retries exceeded")
    void shouldMarkAsFailedAfterMaxRetries() {
        // Arrange - создаем событие с полностью невалидным JSON (некорректный синтаксис)
        String badPayload = "{invalid json here, missing quotes and proper structure";
        OutboxEvent event = OutboxEvent.builder()
                .eventType("LowStockAlert")
                .payload(badPayload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);
        Long eventId = event.getId();

        // Act - запускаем релей до тех пор, пока событие не перейдет в FAILED
        // Максимум 3 ретрая + бэкофф 5 сек
        for (int attempt = 1; attempt <= 3; attempt++) {
            outboxEventRelay.relayPendingEvents();

            // Проверяем статус после каждой попытки
            OutboxEvent updatedEvent = outboxEventRepository.findById(eventId)
                    .orElseThrow(() -> new AssertionError("Event not found"));

            if (attempt < 3) {
                assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
                assertThat(updatedEvent.getRetryCount()).isEqualTo(attempt);
                assertThat(updatedEvent.getLastAttemptAt()).isNotNull();
                assertThat(updatedEvent.getErrorMessage()).isNotNull();
            } else {
                // После 3-й попытки событие должно остаться FAILED
                assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
                assertThat(updatedEvent.getRetryCount()).isEqualTo(3);
            }
        }

        // Assert - проверяем финальное состояние
        OutboxEvent finalEvent = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new AssertionError("Event not found"));

        assertThat(finalEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(finalEvent.getRetryCount()).isEqualTo(3);
        assertThat(finalEvent.getErrorMessage()).contains("Max retries exceeded");
        assertThat(finalEvent.getLastAttemptAt()).isNotNull();

        // Проверяем, что релей больше не берет это событие
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(100);
        assertThat(pendingEvents).isEmpty();

        List<OutboxEvent> failedEvents = outboxEventRepository.findFailedEventsForRetry(100);
        // Событие должно быть вFAILED, но не выбрано для ретрая (превышен лимит)
        assertThat(failedEvents).isEmpty();
    }

    /**
     * Проверяет, что FAILED события не выбираются для ретрая, если прошло меньше 5 секунд.
     */
    @Test
    @DisplayName("Should not retry FAILED event before backoff expires")
    void shouldNotRetryBeforeBackoffExpires() {
        // Arrange - создаем событие с битым JSON (некорректный синтаксис)
        String badPayload = "{unterminated string field: \"value";
        OutboxEvent event = OutboxEvent.builder()
                .eventType("LowStockAlert")
                .payload(badPayload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);
        Long eventId = event.getId();

        // Act 1 - первая попытка (сделаем битый payload)
        outboxEventRelay.relayPendingEvents();

        OutboxEvent firstAttempt = outboxEventRepository.findById(eventId)
                .orElseThrow();
        assertThat(firstAttempt.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(firstAttempt.getRetryCount()).isEqualTo(1);

        // Act 2 - быстро запускаем релей повторно (менее 5 сек)
        outboxEventRelay.relayPendingEvents();

        // Assert 2 - событие должно остаться с тем же retry_count (не было повторной попытки)
        OutboxEvent secondAttempt = outboxEventRepository.findById(eventId)
                .orElseThrow();
        assertThat(secondAttempt.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(secondAttempt.getRetryCount()).isEqualTo(1); // не увеличился!
        assertThat(secondAttempt.getLastAttemptAt()).isEqualTo(firstAttempt.getLastAttemptAt()); // время не изменилось

        // Act 3 - ждем больше 5 секунд и запускаем релей
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    outboxEventRelay.relayPendingEvents();
                    OutboxEvent thirdAttempt = outboxEventRepository.findById(eventId).orElseThrow();
                    assertThat(thirdAttempt.getRetryCount()).isEqualTo(2);
                });
    }

    /**
     * Проверяет, что релей игнорирует FAILED события с превышенным лимитом ретраев.
     */
    @Test
    @DisplayName("Should ignore FAILED events with max retries exceeded")
    void shouldIgnoreFailedEventsAfterMaxRetries() {
        // Arrange - создаем 5 событий с битым JSON (некорректный синтаксис JSON)
        for (int i = 0; i < 5; i++) {
            String badPayload = "{" + i + ", missing value for key, \"field\"}";
            OutboxEvent event = OutboxEvent.builder()
                    .eventType("LowStockAlert")
                    .payload(badPayload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(LocalDateTime.now().plusSeconds(i))
                    .build();
            outboxEventRepository.save(event);
        }

        // Act - запускаем релей много раз
        for (int i = 0; i < 20; i++) {
            outboxEventRelay.relayPendingEvents();
        }

        // Assert - все 5 событий должны быть в FAILED статусе
        List<OutboxEvent> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).hasSize(5);
        for (OutboxEvent event : allEvents) {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(event.getRetryCount()).isEqualTo(3); // максимум ретраев
            assertThat(event.getErrorMessage()).contains("Max retries exceeded");
        }

        // Проверяем, что релей больше не берет эти события
        List<OutboxEvent> failedForRetry = outboxEventRepository.findFailedEventsForRetry(100);
        assertThat(failedForRetry).isEmpty();
    }

    /**
     * Проверяет, что битый payload не создает дубликаты в outbox при ретраях.
     */
    @Test
    @DisplayName("Should not create duplicate outbox entries for failed event")
    void shouldNotCreateDuplicateEntries() {
        // Arrange - создаем событие с битым JSON (некорректный синтаксис)
        String badPayload = "{\"itemId\": 1, missing comma, \"field\": \"value\"";
        OutboxEvent event = OutboxEvent.builder()
                .eventType("LowStockAlert")
                .payload(badPayload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);
        Long eventId = event.getId();

        // Act - запускаем релей много раз
        for (int i = 0; i < 10; i++) {
            outboxEventRelay.relayPendingEvents();
        }

        // Assert - должно быть только 1 событие в outbox
        List<OutboxEvent> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).hasSize(1);
        assertThat(allEvents.get(0).getId()).isEqualTo(eventId);

        // Проверяем статус
        OutboxEvent finalEvent = allEvents.get(0);
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(finalEvent.getRetryCount()).isEqualTo(3);
        assertThat(finalEvent.getErrorMessage()).isNotNull();
    }

    /**
     * Проверяет, что при успешной отправке retry_count сбрасывается в 0.
     */
    @Test
    @DisplayName("Should reset retry_count on successful send")
    void shouldResetRetryCountOnSuccess() {
        // Arrange - сначала создаем событие и делаем его FAILED
        String badPayload = "[unterminated array, missing closing bracket";
        OutboxEvent event = OutboxEvent.builder()
                .eventType("LowStockAlert")
                .payload(badPayload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);

        // Делаем 2 ретрая (но не 3, чтобы не стал FAILED навсегда)
        for (int i = 0; i < 2; i++) {
            outboxEventRelay.relayPendingEvents();
        }

        OutboxEvent afterRetries = outboxEventRepository.findById(event.getId())
                .orElseThrow();
        assertThat(afterRetries.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(afterRetries.getRetryCount()).isEqualTo(2);

        // Act - обновляем событие обратно в PENDING с валидным payload
        String validPayload = "{"
                + "\"itemId\": " + testItemId + ","
                + "\"sku\": \"" + testItem.getSku() + "\","
                + "\"itemName\": \"" + testItem.getName() + "\","
                + "\"currentStock\": 5,"
                + "\"minStock\": 10,"
                + "\"triggeredBy\": \"" + testUser.getUsername() + "\","
                + "\"triggeredAt\": \"" + LocalDateTime.now() + "\""
                + "}";
        afterRetries.setStatus(OutboxStatus.PENDING);
        afterRetries.setPayload(validPayload);
        afterRetries.setRetryCount(0);
        afterRetries.setErrorMessage(null);
        afterRetries.setLastAttemptAt(null);
        outboxEventRepository.save(afterRetries);

        // Запускаем релей — должен успешно отправить
        outboxEventRelay.relayPendingEvents();

        // Assert - проверяем, что событие отправлено и retry_count сброшен
        OutboxEvent successEvent = outboxEventRepository.findById(event.getId())
                .orElseThrow();
        assertThat(successEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(successEvent.getRetryCount()).isEqualTo(0);
        assertThat(successEvent.getErrorMessage()).isNull();
        assertThat(successEvent.getSentAt()).isNotNull();
    }

    /**
     * Проверяет, что error_message содержит информацию об ошибке.
     */
    @Test
    @DisplayName("Should store error message in event")
    void shouldStoreErrorMessage() {
        // Arrange - создаем событие с битым JSON (некорректный синтаксис)
        String badPayload = "{\"itemId\": \"not a number\", \"currentStock\": }";
        OutboxEvent event = OutboxEvent.builder()
                .eventType("LowStockAlert")
                .payload(badPayload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);

        // Act - запускаем релей 3 раза (до FAILED)
        for (int i = 0; i < 3; i++) {
            outboxEventRelay.relayPendingEvents();
        }

        // Assert - проверяем error_message
        OutboxEvent failedEvent = outboxEventRepository.findById(event.getId())
                .orElseThrow();
        assertThat(failedEvent.getErrorMessage()).isNotNull();
        assertThat(failedEvent.getErrorMessage()).contains("Max retries exceeded");
        assertThat(failedEvent.getErrorMessage()).contains("3 attempts");
    }
}
