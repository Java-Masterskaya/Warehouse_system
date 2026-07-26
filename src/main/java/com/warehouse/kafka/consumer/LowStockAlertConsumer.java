package com.warehouse.kafka.consumer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Kafka consumer for processing low stock alert events.
 * <p>
 * Listens to the {@code low-stock-alerts} topic and persists alert records to the database.
 * Distributed tracing context is extracted from incoming Kafka headers and propagated
 * to maintain end-to-end trace visibility across the producer-consumer boundary.
 * Uses custom container factory with errorHandler and DLT for fault tolerance.
 * Duplicate alerts (unique index violation) are logged and skipped without retry.
 * </p>
 *
 * @see LowStockAlertEvent
 * @see com.warehouse.entity.StockAlert
 */
@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LowStockAlertConsumer {

    StockAlertRepository stockAlertRepository;
    ItemRepository itemRepository;
    Tracer tracer;
    Propagator propagator;

    private static final int TRACEPARENT_PARTS_COUNT = 3;

    /**
     * Logs bean initialization for startup diagnostics.
     */
    @PostConstruct
    public void init() {
        log.info("=== LowStockAlertConsumer BEAN INITIALIZED ===");
    }

    /**
     * Consumes a low stock alert event from Kafka.
     * <p>
     * Extracts the distributed tracing context from the incoming message headers,
     * creates a new span as a child of the producer span, and persists the alert
     * to the database. Uses {@code kafkaListenerContainerFactory} with built-in
     * errorHandler and DLT support. Duplicate alerts are skipped gracefully.
     * Uses {@code insertIgnore} to handle duplicates efficiently at database level.
     * </p>
     *
     * @param record the incoming Kafka consumer record containing the alert event
     */
    @KafkaListener(
            topics = "low-stock-alerts",
            groupId = "warehouse-alerts",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(ConsumerRecord<String, LowStockAlertEvent> record) {
        Span span = null;
        try {
            Headers headers = record.headers();
            span = createSpanFromHeaders(headers);

            try (var scope = tracer.withSpan(span)) {
                setupMdc(span);
                logProcessingStart(record);
                logTraceContext(headers, span);

                LowStockAlertEvent event = record.value();
                logEventData(event);
                enrichSpanWithMetadata(span, record, event);

                processAlert(event, span);
            }
        } finally {
            if (span != null) {
                span.end();
            }
            MDC.clear();
        }
    }

    private Span createSpanFromHeaders(Headers headers) {
        Propagator.Getter<Headers> getter = (headers1, key) -> {
            var header = headers1.lastHeader(key);
            if (header != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
            return null;
        };

        return propagator.extract(headers, getter)
                .name("consume-low-stock-alert")
                .start();
    }

    private void setupMdc(Span span) {
        MDC.put("traceId", span.context().traceId());
        MDC.put("spanId", span.context().spanId());
    }

    private void logProcessingStart(ConsumerRecord<String, LowStockAlertEvent> record) {
        log.info("=== CONSUMER STARTED PROCESSING ===");
        log.info("Consumer record: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
    }

    private void logTraceContext(Headers headers, Span span) {
        String parentTraceId = extractHeaderValue(headers, "traceparent")
                .map(this::extractTraceId)
                .orElse(null);
        String parentSpanId = extractHeaderValue(headers, "traceparent")
                .map(this::extractSpanId)
                .orElse(null);

        if (parentTraceId != null) {
            log.info("Parent traceId from Kafka header: {}, parent spanId: {}",
                    parentTraceId, parentSpanId);
        }

        log.info("Current span traceId: {}, spanId: {}",
                span.context().traceId(), span.context().spanId());
    }

    private void logEventData(LowStockAlertEvent event) {
        log.info("Event data: itemId={}, currentStock={}, minStock={}",
                event.itemId(), event.currentStock(), event.minStock());
    }

    private void enrichSpanWithMetadata(Span span, ConsumerRecord<String, LowStockAlertEvent> record,
                                        LowStockAlertEvent event) {
        span.tag("kafka.topic", "low-stock-alerts");
        span.tag("kafka.group", "warehouse-alerts");
        span.tag("kafka.partition", String.valueOf(record.partition()));
        span.tag("kafka.offset", String.valueOf(record.offset()));
        span.tag("item.id", String.valueOf(event.itemId()));
        span.tag("current.stock", String.valueOf(event.currentStock()));
        span.tag("min.stock", String.valueOf(event.minStock()));
    }

    /**
     * Processes the alert event and persists it to the database.
     * <p>
     * Uses {@code insertIgnore} to handle duplicate alerts efficiently.
     * If insert returns 1 - record was inserted, if 0 - duplicate skipped.
     * Throws {@code EntityNotFoundException} if item doesn't exist.
     * Other errors are re-thrown to the container's errorHandler,
     * which routes them to the Dead Letter Topic after configured retries.
     * </p>
     *
     * @param event the low stock alert event
     * @param span  the current tracing span
     */
    private void processAlert(LowStockAlertEvent event, Span span) {
        log.info("Processing low stock alert for itemId={}, currentStock={}, minStock={}",
                event.itemId(), event.currentStock(), event.minStock());

        var item = itemRepository.findById(event.itemId())
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + event.itemId()));

        try {
            log.info("Saving StockAlert to database using insertIgnore...");

            int inserted = stockAlertRepository.insertIgnore(
                    event.itemId(),
                    event.currentStock(),
                    event.minStock(),
                    event.triggeredBy(),
                    event.triggeredAt()
            );

            if (inserted == 1) {
                log.info("StockAlert saved for itemId={}", event.itemId());
                span.tag("alert.status", "saved");
            } else {
                log.warn("Duplicate alert skipped (insertIgnore returned 0): itemId={}, triggeredAt={}",
                        event.itemId(), event.triggeredAt());
                span.tag("alert.status", "duplicate_skipped");
                // Не пробрасываем — дубликат не идёт в DLT
            }

            log.info("=== CONSUMER PROCESSING COMPLETED SUCCESSFULLY ===");

        } catch (Exception e) {
            log.error("Error while processing low stock alert for itemId={}", event.itemId(), e);
            span.error(e);
            throw e; // Пробрасываем в errorHandler → DLT
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
        String[] parts = traceparent.split("-");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    private String extractSpanId(String traceparent) {
        String[] parts = traceparent.split("-");
        if (parts.length >= TRACEPARENT_PARTS_COUNT) {
            return parts[2];
        }
        return null;
    }
}