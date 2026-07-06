package com.warehouse.kafka;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.metric.MetricService;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.retry.maxAttempts=3"
})
class KafkaStockAlertProducerRetryTest {

    @Autowired
    private KafkaStockAlertProducer producer;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private MetricService metricService;

    @MockitoBean
    private Tracer tracer;

    @MockitoBean
    private Propagator propagator;

    @Test
    void sendLowStockAlertShouldRetryThreeTimesOnFailure() {
        // Arrange
        LowStockAlertEvent alert = new LowStockAlertEvent(
                1L,
                "KEY-001",
                "Тестовый товар",
                2,
                5,
                "admin",
                LocalDateTime.now());

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));

        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failedFuture);

        // Act & Assert
        assertThrows(RuntimeException.class, () -> producer.sendLowStockAlert(alert));


        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        verify(metricService, never()).increment(anyString());
    }
}