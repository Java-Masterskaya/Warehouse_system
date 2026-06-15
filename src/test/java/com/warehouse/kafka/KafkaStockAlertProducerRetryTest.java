package com.warehouse.kafka;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class KafkaStockAlertProducerRetryTest {

    private static final Long ITEM_ID = 1L;
    private static final String ITEM_SKU = "KEY-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 2;
    private static final int MIN_STOCK = 5;
    private static final String TRIGGERED_BY = "admin";

    @Autowired
    private KafkaStockAlertProducer producer;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void sendLowStockAlertShouldRetryThreeTimesOnFailure() {
        // Arrange
        LowStockAlertEvent alert = new LowStockAlertEvent(
                ITEM_ID,
                ITEM_SKU,
                ITEM_NAME,
                CURRENT_STOCK,
                MIN_STOCK,
                TRIGGERED_BY,
                LocalDateTime.now());

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(failedFuture);

        // Act & Assert
        assertThrows(RuntimeException.class, () -> producer.sendLowStockAlert(alert));

        // Проверяем, что send был вызван 3 раза (1 попытка + 2 retry)
        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any());
    }
}