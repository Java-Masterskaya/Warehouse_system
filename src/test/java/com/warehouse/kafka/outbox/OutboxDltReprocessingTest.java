package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты для репроцессинга событий из DLT (Dead Letter Table).
 * Проверяет восстановление и повторную обработку событий, перемещенных в DLT.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDltReprocessingTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaStockAlertProducer kafkaProducer;

    private ObjectMapper objectMapper;
    private OutboxEventRelay relay;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        relay = new OutboxEventRelay(outboxEventRepository, kafkaProducer, objectMapper);

        setField(relay, "maxRetries", 3);
        setField(relay, "retryBackoffMs", 5000L);
        setField(relay, "pollingLimit", 100);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("Should process event restored from DLT to PENDING")
    void shouldProcessEventRestoredFromDlt() {
        String validPayload = """
            {"itemId":50,"sku":"SKU-RESTORE","itemName":"Test Item",
            "currentStock":5,"minStock":10,"triggeredBy":"admin",
            "triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(50L)
                .status(OutboxStatus.PENDING)
                .payload(validPayload)
                .retryCount(0)
                .lastAttemptAt(null)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxEventRepository.updateToSent(eq(50L), any(LocalDateTime.class)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        verify(outboxEventRepository).updateToSent(eq(50L), any(LocalDateTime.class));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("Should not re-process SENT event - idempotent handling")
    void shouldNotReprocessSentEventIdempotent() {
        OutboxEvent event = OutboxEvent.builder()
                .id(54L)
                .status(OutboxStatus.SENT)
                .payload("{\"itemId\":54}")
                .retryCount(0)
                .sentAt(LocalDateTime.now().minusMinutes(5))
                .createdAt(LocalDateTime.now())
                .build();

        relay.processSingleEvent(event);

        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).deleteFromOutbox(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("Should not re-process event with broken JSON already in DLT")
    void shouldNotReprocessBrokenJsonAlreadyInDlt() {
        String validPayload = """
            {"itemId":55,"sku":"SKU-BROKEN-DLT","itemName":"Test","currentStock":5,
            "minStock":10,"triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(55L)
                .status(OutboxStatus.PERMANENT_FAILURE)
                .payload(validPayload)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        relay.processSingleEvent(event);

        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).deleteFromOutbox(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("Should move broken JSON event to DLT without increasing retryCount")
    void shouldMoveBrokenJsonToDltWithoutRetries() {
        String brokenJson = """
            {"itemId":53,"sku":"SKU-BROKEN-RETRY","itemName":"Test",
            "currentStock":1,"minStock":10,"triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00",}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(53L)
                .status(OutboxStatus.PENDING)
                .payload(brokenJson)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxEventRepository.insertIntoDlt(eq(53L), any(), eq(0), any(LocalDateTime.class)))
                .thenReturn(333L);
        when(outboxEventRepository.deleteFromOutbox(eq(53L)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        verify(outboxEventRepository).insertIntoDlt(
                eq(53L),
                any(),
                eq(0),
                any(LocalDateTime.class)
        );
        verify(outboxEventRepository).deleteFromOutbox(eq(53L));
        // updateToPermanentFailure НЕ вызывается, так как deleteFromOutbox успешен (deleted > 0)
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
        assertThat(event.getRetryCount()).isZero();
    }
}