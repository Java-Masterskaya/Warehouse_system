package com.warehouse.kafka;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.metric.MetricService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Unit-тест для KafkaStockAlertProducer.
 * Тестирует отправку сообщений о низких остатках в Kafka.
 */
@ExtendWith(MockitoExtension.class)
class KafkaStockAlertProducerTest {
    private static final String TOPIC_NAME = "low-stock-alerts";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private MetricService metricService;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @Mock
    private Span span;

    private KafkaStockAlertProducer producer;

    private static final Long ITEM_ID = 1L;
    private static final String ITEM_SKU = "KEY-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 2;
    private static final int MIN_STOCK = 5;
    private static final String TRIGGERED_BY = "admin";

    @BeforeEach
    void setUp() {
        when(tracer.currentSpan()).thenReturn(span);
        producer = new KafkaStockAlertProducer(kafkaTemplate, metricService, tracer, propagator);
    }

    /**
     * Отправка low stock alert без исключений.
     */
    @Test
    void sendLowStockAlertShouldSendMessageWithoutException() {
        // Arrange
        LowStockAlertEvent alert = createAlert();

        TopicPartition topicPartition = new TopicPartition(TOPIC_NAME, 0);
        RecordMetadata recordMetadata = new RecordMetadata(topicPartition, 0, 0, 0, 0, 0);
        SendResult<String, Object> sendResult = new SendResult<>(null, recordMetadata);

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // Act & Assert
        assertDoesNotThrow(() -> producer.sendLowStockAlert(alert));

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(metricService, times(1)).increment("warehouse.stock.low_alert.total");
    }

    /**
     * ItemId используется как ключ сообщения.
     */
    @Test
    void sendLowStockAlertShouldUseItemIdAsKey() {
        // Arrange
        Long specificItemId = 42L;
        LowStockAlertEvent alert = new LowStockAlertEvent(
                specificItemId,
                ITEM_SKU,
                ITEM_NAME,
                CURRENT_STOCK,
                MIN_STOCK,
                TRIGGERED_BY,
                LocalDateTime.now());

        TopicPartition topicPartition = new TopicPartition(TOPIC_NAME, 1);
        RecordMetadata recordMetadata = new RecordMetadata(topicPartition, 0, 0, 0, 0, 0);
        SendResult<String, Object> sendResult = new SendResult<>(null, recordMetadata);

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        // Act
        producer.sendLowStockAlert(alert);

        // Assert – проверяем, что ProducerRecord создан с правильными параметрами
        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> record) ->
                record.topic().equals(TOPIC_NAME)
                        && record.key().equals(String.valueOf(specificItemId))
                        && record.value().equals(alert)
        ));
        verify(metricService, times(1)).increment("warehouse.stock.low_alert.total");
    }

    /**
     * При ошибке отправки выбрасывается RuntimeException.
     */
    @Test
    void sendLowStockAlertShouldThrowRuntimeExceptionWhenSendFails() {
        // Arrange
        LowStockAlertEvent alert = createAlert();

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        // Act & Assert
        assertThrows(RuntimeException.class,
                () -> producer.sendLowStockAlert(alert));

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(metricService, never()).increment(anyString());
    }

    private LowStockAlertEvent createAlert() {
        return new LowStockAlertEvent(
                ITEM_ID,
                ITEM_SKU,
                ITEM_NAME,
                CURRENT_STOCK,
                MIN_STOCK,
                TRIGGERED_BY,
                LocalDateTime.now());
    }
}