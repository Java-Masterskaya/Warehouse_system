package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventRelayRetryTest {

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

    // ==================== УСПЕШНАЯ ОТПРАВКА ====================

    @Test
    @DisplayName("Should mark event as SENT on successful Kafka send")
    void shouldMarkAsSentOnSuccess() {
        String validPayload = """
            {"itemId":1,"sku":"SKU-001","itemName":"Test Item","currentStock":4,
            "minStock":10,"triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(1L)
                .status(OutboxStatus.PENDING)
                .payload(validPayload)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxEventRepository.updateToSent(eq(1L), any(LocalDateTime.class)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        verify(kafkaProducer).sendLowStockAlert(any(LowStockAlertEvent.class));
        verify(outboxEventRepository).updateToSent(eq(1L), any(LocalDateTime.class));
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getErrorMessage()).isNull();
    }

    // ==================== RETRY СЦЕНАРИИ ====================

    @Test
    @DisplayName("Should mark as FAILED with incremented retry count on transient error")
    void shouldMarkAsFailedOnTransientError() {
        String validPayload = """
            {"itemId":2,"sku":"SKU-002","itemName":"Test","currentStock":3,"minStock":10,
            "triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(2L)
                .status(OutboxStatus.PENDING)
                .payload(validPayload)
                .retryCount(0)
                .lastAttemptAt(null)
                .createdAt(LocalDateTime.now())
                .build();

        RuntimeException kafkaException = new RuntimeException("Broker unavailable");

        doThrow(kafkaException).when(kafkaProducer).sendLowStockAlert(any());

        when(outboxEventRepository.updateToFailed(eq(2L), anyString(), eq(1), any(LocalDateTime.class)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        verify(kafkaProducer).sendLowStockAlert(any());
        verify(outboxEventRepository).updateToFailed(
                eq(2L),
                eq("Broker unavailable"),
                eq(1),
                any(LocalDateTime.class)
        );

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastAttemptAt()).isNotNull();
        // event.getErrorMessage() не обновляется в коде (только в БД через updateToFailed)

        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).updateToSent(any(), any());
    }

    @Test
    @DisplayName("Should retry FAILED event when backoff has elapsed")
    void shouldRetryFailedEventWhenBackoffElapsed() {
        String validPayload = """
            {"itemId":3,"sku":"SKU-003","itemName":"Test","currentStock":2,"minStock":10,
            "triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(3L)
                .status(OutboxStatus.FAILED)
                .payload(validPayload)
                .retryCount(1)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(10))
                .createdAt(LocalDateTime.now())
                .build();

        RuntimeException kafkaException = new RuntimeException("Broker still unavailable");

        doThrow(kafkaException).when(kafkaProducer).sendLowStockAlert(any());

        when(outboxEventRepository.updateToFailed(eq(3L), anyString(), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        verify(kafkaProducer).sendLowStockAlert(any());
        verify(outboxEventRepository).updateToFailed(
                eq(3L),
                anyString(),
                eq(2),
                any(LocalDateTime.class)
        );

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip FAILED event when backoff has not elapsed (before Kafka call)")
    void shouldSkipFailedEventWhenBackoffNotElapsed() {
        String validPayload = """
            {"itemId":4,"sku":"SKU-004","itemName":"Test","currentStock":2,"minStock":10,
            "triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(4L)
                .status(OutboxStatus.FAILED)
                .payload(validPayload)
                .retryCount(1)
                .lastAttemptAt(LocalDateTime.now().minusSeconds(1))  // Бэкофф не прошёл (1 сек < 5 сек)
                .createdAt(LocalDateTime.now())
                .build();

        // После исправления: backoff проверяется ДО вызова Kafka
        // Поэтому kafkaProducer НЕ должен быть вызван
        relay.processSingleEvent(event);

        // kafkaProducer НЕ вызывается из-за backoff (backoff проверяется до sendLowStockAlert)
        verify(kafkaProducer, never()).sendLowStockAlert(any());
        // updateToFailed НЕ вызывается из-за backoff
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    /**
     * Проверяет, что backoff проверяется ДО вызова Kafka для FAILED событий.
     * Это исправление бага: ранее backoff проверялся только внутри catch блока,
     * после неудачного вызова Kafka.
     */
    @Test
    @DisplayName("Should check backoff BEFORE Kafka call for FAILED events")
    void shouldCheckBackoffBeforeKafkaCall() {
        String validPayload = """
            {"itemId":100,"sku":"SKU-BACKOFF-BEFORE","itemName":"Test","currentStock":2,
            "minStock":10,"triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(100L)
                .status(OutboxStatus.FAILED)
                .payload(validPayload)
                .retryCount(1)
                .lastAttemptAt(LocalDateTime.now().minusSeconds(2))  // Бэкофф 5 сек, прошло только 2 сек
                .createdAt(LocalDateTime.now())
                .build();

        relay.processSingleEvent(event);

        // Проверяем, что Kafka вызван НЕ БЫЛ из-за backoff
        verify(kafkaProducer, never()).sendLowStockAlert(any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());

        // Статус остаётся FAILED
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should move event to DLT when max retries exceeded")
    void shouldMoveToDltWhenMaxRetriesExceeded() {
        String validPayload = """
            {"itemId":5,"sku":"SKU-005","itemName":"Test","currentStock":1,"minStock":10,
            "triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00"}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(5L)
                .status(OutboxStatus.PENDING)
                .payload(validPayload)
                .retryCount(2)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(10))
                .createdAt(LocalDateTime.now())
                .build();

        RuntimeException kafkaException = new RuntimeException("Persistent broker failure");

        doThrow(kafkaException).when(kafkaProducer).sendLowStockAlert(any());

        when(outboxEventRepository.insertIntoDlt(eq(5L), anyString(), eq(3), any(LocalDateTime.class)))
                .thenReturn(888L);
        when(outboxEventRepository.deleteFromOutbox(eq(5L)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(kafkaProducer).sendLowStockAlert(any());

        verify(outboxEventRepository).insertIntoDlt(
                eq(5L),
                errorCaptor.capture(),
                eq(3),
                timeCaptor.capture()
        );

        assertThat(errorCaptor.getValue())
                .startsWith("Max retries exceeded (3 attempts)")
                .contains("Persistent broker failure");

        verify(outboxEventRepository).deleteFromOutbox(eq(5L));
        // updateToPermanentFailure НЕ вызывается, так как deleteFromOutbox успешен (deleted > 0)
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
        assertThat(event.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle already processed event idempotently")
    void shouldSkipAlreadyProcessedEvent() {
        OutboxEvent event = OutboxEvent.builder()
                .id(6L)
                .status(OutboxStatus.SENT)
                .payload("{\"itemId\":6}")
                .retryCount(0)
                .build();

        relay.processSingleEvent(event);

        verifyNoInteractions(kafkaProducer);
        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Should handle PERMANENT_FAILURE event idempotently")
    void shouldSkipPermanentFailureEvent() {
        OutboxEvent event = OutboxEvent.builder()
                .id(7L)
                .status(OutboxStatus.PERMANENT_FAILURE)
                .payload("{\"itemId\":7}")
                .retryCount(3)
                .build();

        relay.processSingleEvent(event);

        verifyNoInteractions(kafkaProducer);
        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
    }

    /**
     * Проверяет, что событие, которое уже было перемещено в DLT,
     * не обрабатывается повторно релаем (идемпотентность).
     */
    @Test
    @DisplayName("Should not process PERMANENT_FAILURE event - already moved to DLT")
    void shouldNotProcessEventAlreadyInDlt() {
        OutboxEvent event = OutboxEvent.builder()
                .id(100L)
                .status(OutboxStatus.PERMANENT_FAILURE)
                .payload("{\"itemId\":100,\"sku\":\"SKU-DLT-SKIP\",\"itemName\":\"Test\","
                        + "\"currentStock\":5,\"minStock\":10,\"triggeredBy\":\"admin\","
                        + "\"triggeredAt\":\"2026-07-10T18:00:00\"}")
                .retryCount(3)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(10))
                .createdAt(LocalDateTime.now())
                .build();

        relay.processSingleEvent(event);

        // Проверяем, что событие пропущено (никаких операций с репозиторием)
        verifyNoInteractions(kafkaProducer);
        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).deleteFromOutbox(any());
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());

        // Статус не должен измениться
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
        assertThat(event.getRetryCount()).isEqualTo(3);
    }

    /**
     * Проверяет идемпотентность при повторной обработке SENT события.
     * Событие уже отправлено в Kafka, повторная обработка не должна создать дубликат.
     */
    @Test
    @DisplayName("Should not re-process SENT event - already successfully sent")
    void shouldNotReprocessSentEvent() {
        String validPayload = "{\"itemId\":200,\"sku\":\"SKU-SENT\",\"itemName\":\"Test\",\"currentStock\":5,"
                + "\"minStock\":10,\"triggeredBy\":\"admin\",\"triggeredAt\":\"2026-07-10T18:00:00\"}";

        OutboxEvent event = OutboxEvent.builder()
                .id(200L)
                .status(OutboxStatus.SENT)
                .payload(validPayload)
                .retryCount(0)
                .sentAt(LocalDateTime.now().minusMinutes(5))
                .createdAt(LocalDateTime.now())
                .build();

        relay.processSingleEvent(event);

        // Проверяем, что событие пропущено
        verifyNoInteractions(kafkaProducer);
        verify(outboxEventRepository, never()).updateToSent(any(), any());
        verify(outboxEventRepository, never()).updateToFailed(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).insertIntoDlt(any(), any(), anyInt(), any());
        verify(outboxEventRepository, never()).deleteFromOutbox(any());

        // Статус не должен измениться
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
    }
}