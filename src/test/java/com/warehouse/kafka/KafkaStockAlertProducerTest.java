package com.warehouse.kafka;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для KafkaStockAlertProducer: отправка уведомлений о низком остатке.
 * Проверяют: успешная отправка, корректный ключ, обработка ошибок Kafka.
 */
@ExtendWith(MockitoExtension.class)
class KafkaStockAlertProducerTest {
    private static final String TOPIC_NAME = "low-stock-alerts";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaStockAlertProducer producer;

    private static final Long ITEM_ID = 1L;
    private static final String ITEM_SKU = "KEY-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 2;
    private static final int MIN_STOCK = 5;
    private static final String TRIGGERED_BY = "admin";

    @BeforeEach
    void setUp() {
        producer = new KafkaStockAlertProducer(kafkaTemplate);
    }

    /**
     * Отправка уведомления о низком остатке проходит без исключений.
     */
    @Test
    void sendLowStockAlertShouldSendMessageWithoutException() {
        // Arrange
        LowStockAlertEvent alert = createAlert();

        // Создаем мок результата отправки
        TopicPartition topicPartition = new TopicPartition(TOPIC_NAME, 0);
        RecordMetadata recordMetadata = new RecordMetadata(topicPartition, 0, 0, 0,
                0, 0);
        SendResult<String, Object> sendResult = new SendResult<>(null, recordMetadata);

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq(TOPIC_NAME), eq(String.valueOf(ITEM_ID)), eq(alert))).thenReturn(future);

        // Act & Assert
        assertDoesNotThrow(() -> producer.sendLowStockAlert(alert));

        // Verify (что send вызван с правильными параметрами)
        verify(kafkaTemplate, times(1)).send(TOPIC_NAME, String.valueOf(ITEM_ID), alert);
    }

    /**
     * Уведомление отправляется с itemId в качестве ключа.
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
        RecordMetadata recordMetadata = new RecordMetadata(topicPartition, 0,
                0, 0, 0, 0);
        SendResult<String, Object> sendResult = new SendResult<>(null, recordMetadata);

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq(TOPIC_NAME), eq(String.valueOf(specificItemId)), eq(alert)))
                .thenReturn(future);

        // Act
        producer.sendLowStockAlert(alert);

        // Assert – проверяем, что send был вызван ровно один раз с ключом "42"
        verify(kafkaTemplate).send(TOPIC_NAME, "42", alert);
    }

    /**
     * Ошибка отправки в Kafka приводит к RuntimeException.
     */
    @Test
    void sendLowStockAlertShouldThrowRuntimeExceptionWhenSendFails() {
        // Arrange
        LowStockAlertEvent alert = createAlert();

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));

        when(kafkaTemplate.send(eq(TOPIC_NAME), anyString(), any(LowStockAlertEvent.class)))
                .thenReturn(failedFuture);

        // Act & Assert
        assertThrows(RuntimeException.class,
                () -> producer.sendLowStockAlert(alert));

        // Проверяем, что send был вызван один раз
        // Ретраи не тестируем тут, нет Spring-контекста и  @Retryable не активен
        verify(kafkaTemplate, times(1)).send(TOPIC_NAME, String.valueOf(ITEM_ID), alert);
    }

    /**
     * Вспомогательный метод для создания LowStockAlertEvent.
     */
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