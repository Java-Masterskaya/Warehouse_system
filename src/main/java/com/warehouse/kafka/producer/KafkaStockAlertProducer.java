package com.warehouse.kafka.producer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.metric.MetricService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaStockAlertProducer implements KafkaProducerService {
    private static final String TOPIC_NAME = "low-stock-alerts";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MetricService metricService;

    public KafkaStockAlertProducer(KafkaTemplate<String, Object> kafkaTemplate, MetricService metricService) {
        this.kafkaTemplate = kafkaTemplate;
        this.metricService = metricService;
    }

    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void sendLowStockAlert(LowStockAlertEvent alert) {
        log.debug("Received low stock alert: {}", alert);
        try {
            SendResult<String, Object> result = kafkaTemplate.send(
                    TOPIC_NAME,
                    String.valueOf(alert.itemId()),
                    alert
            ).get();
            log.info("Successfully sent to topic {} для itemId {}. Partition: {}, Offset: {}",
                    TOPIC_NAME, alert.itemId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            metricService.increment("warehouse.stock.low_alert.total");
        } catch (Exception e) {
            log.error("Error while sending low stock alert to Kafka", e);
            throw new RuntimeException("Error while sending message to Kafka", e);
        }
    }
}