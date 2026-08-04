package com.warehouse.kafka.producer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.metric.MetricService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class KafkaStockAlertProducer implements KafkaProducerService {

    private static final String TOPIC_NAME = "low-stock-alerts";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MetricService metricService;
    private final Tracer tracer;
    private final Propagator propagator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public KafkaStockAlertProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                   MetricService metricService,
                                   Tracer tracer,
                                   Propagator propagator,
                                   CircuitBreakerRegistry circuitBreakerRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.metricService = metricService;
        this.tracer = tracer;
        this.propagator = propagator;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "sendLowStockAlertFallback")
    public void sendLowStockAlert(LowStockAlertEvent alert) {
        Span span = tracer.currentSpan();

        ProducerRecord<String, Object> record = new ProducerRecord<>(
                TOPIC_NAME,
                String.valueOf(alert.itemId()),
                alert
        );

        if (span != null) {
            span.tag("kafka.topic", TOPIC_NAME);
            span.tag("item.id", String.valueOf(alert.itemId()));
            span.tag("current.stock", String.valueOf(alert.currentStock()));

            propagator.inject(span.context(), record.headers(),
                    (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8))
            );
        }

        log.debug("Received low stock alert: {}", alert);

        try {
            SendResult<String, Object> result = kafkaTemplate.send(record).get();

            if (span != null) {
                span.tag("kafka.partition", String.valueOf(result.getRecordMetadata().partition()));
                span.tag("kafka.offset", String.valueOf(result.getRecordMetadata().offset()));
            }

            log.info("Successfully sent to topic {} для itemId {}. Partition: {}, Offset: {}",
                    TOPIC_NAME, alert.itemId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            metricService.increment("warehouse.stock.low_alert.total");

        } catch (Exception e) {
            if (span != null) {
                span.error(e);
            }
            log.error("Error while sending low stock alert to Kafka", e);
            throw new RuntimeException("Error while sending message to Kafka", e);
        }
    }

    @SuppressWarnings("unused")
    public void sendLowStockAlertFallback(LowStockAlertEvent alert, Throwable t) {
        var state = circuitBreakerRegistry
                .circuitBreaker("kafkaProducer")
                .getState();
        log.warn("kafkaProducer call failed (breaker state = {}), alert not sent to Kafka. "
                + "Event will be retried by outbox relay. Error: {}", state, t.getMessage());
        metricService.increment("warehouse.kafka.producer.circuit_open");

        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        throw new RuntimeException(t);
    }
}
