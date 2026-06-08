package com.warehouse.kafka;

import com.warehouse.dto.event.LowStockAlert;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class KafkaStockAlertProducerRetryTest {

    @Autowired
    private KafkaStockAlertProducer producer;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void sendLowStockAlertShouldRetryThreeTimesOnFailure() {
        // Arrange
        LowStockAlert alert = new LowStockAlert(1L, "Test", 2, 5);

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