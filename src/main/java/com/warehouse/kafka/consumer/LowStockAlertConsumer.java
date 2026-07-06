package com.warehouse.kafka.consumer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.StockAlert;
import com.warehouse.mapper.StockAlertMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LowStockAlertConsumer {

    StockAlertRepository stockAlertRepository;
    StockAlertMapper stockAlertMapper;
    ItemRepository itemRepository;
    Tracer tracer;
    Propagator propagator;

    @PostConstruct
    public void init() {
        log.info("=== LowStockAlertConsumer BEAN INITIALIZED ===");
    }

    @KafkaListener(
            topics = "low-stock-alerts",
            groupId = "warehouse-alerts",
            properties = {
                    "auto.offset.reset=earliest"
            }
    )
    public void consume(ConsumerRecord<String, LowStockAlertEvent> record) {
        Span span = null;
        try {
            Headers headers = record.headers();

            // Извлекаем traceId и spanId из заголовков
            String parentTraceId = extractHeaderValue(headers, "traceparent")
                    .map(this::extractTraceId)
                    .orElse(null);
            String parentSpanId = extractHeaderValue(headers, "traceparent")
                    .map(this::extractSpanId)
                    .orElse(null);

            // Используем propagator для извлечения контекста
            Propagator.Getter<Headers> getter = (headers1, key) -> {
                var header = headers1.lastHeader(key);
                if (header != null) {
                    return new String(header.value(), StandardCharsets.UTF_8);
                }
                return null;
            };

            // Создаем span из извлеченного контекста
            span = propagator.extract(headers, getter)
                    .name("consume-low-stock-alert")
                    .start();

            // Устанавливаем span как текущий
            try (var scope = tracer.withSpan(span)) {

                // Устанавливаем MDC
                MDC.put("traceId", span.context().traceId());
                MDC.put("spanId", span.context().spanId());

                log.info("=== CONSUMER STARTED PROCESSING ===");
                log.info("Consumer record: topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());

                if (parentTraceId != null) {
                    log.info("Parent traceId from Kafka header: {}, parent spanId: {}",
                            parentTraceId, parentSpanId);
                }

                log.info("Current span traceId: {}, spanId: {}",
                        span.context().traceId(), span.context().spanId());

                LowStockAlertEvent event = record.value();
                log.info("Event data: itemId={}, currentStock={}, minStock={}",
                        event.itemId(), event.currentStock(), event.minStock());

                // Добавляем теги
                span.tag("kafka.topic", "low-stock-alerts");
                span.tag("kafka.group", "warehouse-alerts");
                span.tag("kafka.partition", String.valueOf(record.partition()));
                span.tag("kafka.offset", String.valueOf(record.offset()));
                span.tag("item.id", String.valueOf(event.itemId()));
                span.tag("current.stock", String.valueOf(event.currentStock()));
                span.tag("min.stock", String.valueOf(event.minStock()));

                log.info("Received low stock alert for itemId={}, currentStock={}, minStock={}",
                        event.itemId(), event.currentStock(), event.minStock());

                try {
                    log.info("Mapping event to StockAlert entity...");
                    StockAlert alert = stockAlertMapper.toEntity(
                            event,
                            itemRepository.getReferenceById(event.itemId())
                    );
                    log.info("StockAlert mapped");

                    log.info("Saving StockAlert to database...");
                    StockAlert savedAlert = stockAlertRepository.save(alert);
                    log.info("StockAlert saved with id={}", savedAlert.getId());

                    span.tag("alert.id", String.valueOf(savedAlert.getId()));
                    log.info("Alert id tag added to span");

                    log.info("=== CONSUMER PROCESSING COMPLETED SUCCESSFULLY ===");

                } catch (Exception e) {
                    log.error("Error while processing low stock alert for itemId={}", event.itemId(), e);
                    span.error(e);
                    log.error("=== CONSUMER PROCESSING FAILED ===");
                    throw e;
                }
            }
        } finally {
            if (span != null) {
                span.end();
            }
            MDC.clear();
        }
    }

    private java.util.Optional<String> extractHeaderValue(Headers headers, String key) {
        var header = headers.lastHeader(key);
        if (header != null) {
            return java.util.Optional.of(new String(header.value(), StandardCharsets.UTF_8));
        }
        return java.util.Optional.empty();
    }

    private String extractTraceId(String traceparent) {
        // Формат: 00-traceId-spanId-01
        String[] parts = traceparent.split("-");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    private String extractSpanId(String traceparent) {
        // Формат: 00-traceId-spanId-01
        String[] parts = traceparent.split("-");
        if (parts.length >= 3) {
            return parts[2];
        }
        return null;
    }
}