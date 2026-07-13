package com.warehouse.kafka.outbox;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.OutboxDltEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.repository.OutboxDltEventRepository;
import com.warehouse.repository.OutboxEventRepository;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест для проверки репроцессинга событий из Outbox DLT (Dead Letter Table).
 * Проверяет корректность перемещения событий из outbox_dlt обратно в outbox.
 */
@SpringBootTest
@DisplayName("Outbox DLT reprocessing integration test")
@Transactional
class OutboxDltReprocessingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxDltEventRepository outboxDltEventRepository;

    @Autowired
    private OutboxDltReprocessingService outboxDltReprocessingService;

    @Autowired
    private OutboxEventRelay outboxEventRelay;

    @BeforeEach
    void setUp() {
        // Очищаем таблицы перед каждым тестом
        outboxEventRepository.deleteAll();
        outboxDltEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Should restore event from DLT to outbox and relay should process it")
    void shouldRestoreEventFromDltAndRelayShouldProcessIt() {
        // Arrange - создаем событие в DLT
        OutboxDltEvent dltEvent = OutboxDltEvent.builder()
                .originalOutboxId(100L)
                .eventType("LowStockAlert")
                .payload("{\"itemId\":50,\"sku\":\"SKU-DLT\",\"itemName\":\"Test\",\"currentStock\":5,"
                        + "\"minStock\":10,\"triggeredBy\":\"admin\",\"triggeredAt\":\"2026-07-10T18:00:00\"}")
                .errorMessage("Max retries exceeded")
                .retryCount(3)
                .lastAttemptAt(LocalDateTime.now().minusDays(1))
                .permanentFailureReason("MAX_RETRIES_EXCEEDED")
                .dltCreatedAt(LocalDateTime.now().minusDays(1))
                .build();

        outboxDltEventRepository.save(dltEvent);

        Long dltId = dltEvent.getId();
        assertThat(outboxDltEventRepository.count()).isEqualTo(1);

        // Act 1 - репроцессинг из DLT в outbox
        var response = outboxDltReprocessingService.reprocessAllOutboxDltMessages().join();

        // Assert 1 - событие перемещено
        assertThat(response.getTotalMessages()).isEqualTo(1);
        assertThat(response.getReprocessed()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(0);

        assertThat(outboxDltEventRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        OutboxEvent restoredEvent = outboxEventRepository.findAll().get(0);
        assertThat(restoredEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(restoredEvent.getRetryCount()).isEqualTo(0);

        // Act 2 - релей должен обработать событие
        outboxEventRelay.relayPendingEvents();

        // Assert 2 - событие отправлено (в тесте без Kafka, но статус обновится)
        OutboxEvent sentEvent = outboxEventRepository.findById(restoredEvent.getId()).orElseThrow();
        assertThat(sentEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Should handle empty DLT correctly")
    void shouldHandleEmptyDlt() {
        // Arrange - DLT пуста

        // Act
        var response = outboxDltReprocessingService.reprocessAllOutboxDltMessages().join();

        // Assert
        assertThat(response.getTotalMessages()).isEqualTo(0);
        assertThat(response.getReprocessed()).isEqualTo(0);
        assertThat(response.getFailed()).isEqualTo(0);
        assertThat(response.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiple events in DLT")
    void shouldHandleMultipleEventsInDlt() {
        // Arrange - создаем 3 события в DLT
        for (int i = 0; i < 3; i++) {
            OutboxDltEvent dltEvent = OutboxDltEvent.builder()
                    .originalOutboxId(100L + i)
                    .eventType("LowStockAlert")
                    .payload("{\"itemId\":" + (50 + i) + "}")
                    .permanentFailureReason("MAX_RETRIES_EXCEEDED")
                    .retryCount(3)
                    .dltCreatedAt(LocalDateTime.now())
                    .build();
            outboxDltEventRepository.save(dltEvent);
        }

        assertThat(outboxDltEventRepository.count()).isEqualTo(3);

        // Act
        var response = outboxDltReprocessingService.reprocessAllOutboxDltMessages().join();

        // Assert
        assertThat(response.getTotalMessages()).isEqualTo(3);
        assertThat(response.getReprocessed()).isEqualTo(3);
        assertThat(response.getFailed()).isEqualTo(0);

        assertThat(outboxDltEventRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(3);
    }
}
