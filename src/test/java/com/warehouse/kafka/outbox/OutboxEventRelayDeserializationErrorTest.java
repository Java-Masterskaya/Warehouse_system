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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventRelayDeserializationErrorTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaStockAlertProducer kafkaProducer;

    private ObjectMapper objectMapper;
    private OutboxEventRelay relay;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        relay = new OutboxEventRelay(outboxEventRepository, kafkaProducer, objectMapper);
    }

    @Test
    @DisplayName("Should move event to DLT immediately on deserialization error without retry")
    void shouldMoveToDltOnDeserializationError() {
        String brokenJson = """
            {"itemId":1,"sku":"SKU-001","itemName":"Test","currentStock":4,"minStock":10,"triggeredBy":"admin","triggeredAt":"2026-07-10T18:00:00",}
            """;

        OutboxEvent event = OutboxEvent.builder()
                .id(1L)
                .status(OutboxStatus.PENDING)
                .payload(brokenJson)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxEventRepository.insertIntoDlt(eq(1L), any(), eq(0), any(LocalDateTime.class)))
                .thenReturn(999L);
        when(outboxEventRepository.deleteFromOutbox(eq(1L)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(outboxEventRepository, times(1)).insertIntoDlt(
                eq(1L),
                errorCaptor.capture(),
                eq(0),
                timeCaptor.capture()
        );

        assertThat(errorCaptor.getValue())
                .startsWith("Deserialization error:")
                .contains("Unexpected character");

        verify(outboxEventRepository, times(1)).deleteFromOutbox(eq(1L));
        // updateToPermanentFailure НЕ вызывается, так как deleteFromOutbox успешен (deleted > 0)
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());
        verify(kafkaProducer, never()).sendLowStockAlert(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getLastAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Should handle DLT insert failure gracefully")
    void shouldHandleDltInsertFailure() {
        String brokenJson = "{\"invalid\":}";

        OutboxEvent event = OutboxEvent.builder()
                .id(2L)
                .status(OutboxStatus.PENDING)
                .payload(brokenJson)
                .retryCount(0)
                .build();

        when(outboxEventRepository.insertIntoDlt(eq(2L), any(), eq(0), any(LocalDateTime.class)))
                .thenReturn(null);

        relay.processSingleEvent(event);

        verify(outboxEventRepository, never()).deleteFromOutbox(any());
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    /**
     * Проверяет, что событие с битым JSON, которое уже перемещено в DLT,
     * не обрабатывается повторно релаем.
     * Сценарий: событие было перемещено в DLT из-за десериализационной ошибки,
     * затем восстановлено из DLT в PENDING, но при повторной обработке снова битый JSON.
     */
    @Test
    @DisplayName("Should not process event with broken JSON that is already in DLT")
    void shouldNotProcessBrokenJsonAlreadyInDlt() {
        String brokenJson = "{\"itemId\":1,\"sku\":\"SKU-BROKEN\",\"itemName\":\"Test\",\"currentStock\":5,\"minStock\":10,\"triggeredBy\":\"admin\",\"triggeredAt\":\"2026-07-10T18:00:00\",}  // trailing comma - broken JSON";

        // Событие уже было перемещено в DLT, затем восстановлено в PENDING для повторной попытки
        OutboxEvent event = OutboxEvent.builder()
                .id(3L)
                .status(OutboxStatus.PENDING)  // Восстановлено из PERMANENT_FAILURE
                .payload(brokenJson)
                .retryCount(0)  // Сброшен при восстановлении
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxEventRepository.insertIntoDlt(eq(3L), any(), eq(0), any(LocalDateTime.class)))
                .thenReturn(888L);  // DLT insert успешен
        when(outboxEventRepository.deleteFromOutbox(eq(3L)))
                .thenReturn(1);

        relay.processSingleEvent(event);

        // Проверяем, что событие снова перемещено в DLT
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        verify(outboxEventRepository).insertIntoDlt(
                eq(3L),
                errorCaptor.capture(),
                eq(0),  // retryCount не увеличивается для deserialization errors
                any(LocalDateTime.class)
        );

        assertThat(errorCaptor.getValue())
                .startsWith("Deserialization error:")
                .contains("Unexpected character");

        verify(outboxEventRepository).deleteFromOutbox(eq(3L));
        // updateToPermanentFailure НЕ вызывается, так как deleteFromOutbox успешен (deleted > 0)
        verify(outboxEventRepository, never()).updateToPermanentFailure(any());
        verify(kafkaProducer, never()).sendLowStockAlert(any());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
        assertThat(event.getRetryCount()).isZero();
    }

    /**
     * Проверяет идемпотентность при повторной обработке события, которое уже в PERMANENT_FAILURE
     * после десериализационной ошибки.
     */
    @Test
    @DisplayName("Should not re-process event already in PERMANENT_FAILURE from deserialization error")
    void shouldNotReprocessAlreadyDltFromDeserializationError() {
        OutboxEvent event = OutboxEvent.builder()
                .id(4L)
                .status(OutboxStatus.PERMANENT_FAILURE)  // Уже перемещено в DLT
                .payload("{\"itemId\":4,\"sku\":\"SKU-BROKEN-REPROCESS\",\"itemName\":\"Test\",\"currentStock\":5,\"minStock\":10,\"triggeredBy\":\"admin\",\"triggeredAt\":\"2026-07-10T18:00:00\"}")
                .retryCount(0)  // Для deserialization errors retryCount не увеличивается
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
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PERMANENT_FAILURE);
    }
}