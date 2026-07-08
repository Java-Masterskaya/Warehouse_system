package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaStockAlertProducer kafkaProducer;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${spring.kafka.outbox.polling.limit:100}")
    private int pollingLimit;

    @Scheduled(fixedDelayString = "${spring.kafka.outbox.polling.interval-ms:5000}")
    public void relayPendingEvents() {
        log.debug("Checking for pending outbox events, limit={}", pollingLimit);

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(pollingLimit);

        if (pendingEvents.isEmpty()) {
            log.debug("No pending outbox events found");
            return;
        }

        log.info("Found {} pending outbox events, processing...", pendingEvents.size());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        for (OutboxEvent event : pendingEvents) {
            try {
                txTemplate.executeWithoutResult(status -> processSingleEvent(event.getId()));
            } catch (Exception e) {
                // Логируем и продолжаем — событие останется PENDING
                log.error("Failed to process outbox event id={}, eventType={}",
                        event.getId(), event.getEventType(), e);
            }
        }

        log.info("Processed {} outbox events", pendingEvents.size());
    }

    private void processSingleEvent(Long eventId) {
        // Re-fetch в текущей транзакции — гарантируем attached entity
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Event not found: " + eventId));

        if (event.getStatus() != OutboxStatus.PENDING) {
            log.debug("Event id={} already processed (status={}), skipping",
                    eventId, event.getStatus());
            return;
        }

        try {
            LowStockAlertEvent alertEvent = objectMapper.readValue(
                    event.getPayload(),
                    LowStockAlertEvent.class
            );

            kafkaProducer.sendLowStockAlert(alertEvent);

            // WHERE status = PENDING — защита от race condition
            int updated = outboxEventRepository.updateToSent(eventId, LocalDateTime.now());

            if (updated == 0) {
                log.warn("Event id={} not updated to SENT (concurrent processing?)", eventId);
            } else {
                log.debug("Successfully sent event id={} to Kafka", eventId);
            }
        } catch (Exception e) {
            log.error("Error processing outbox event id={}: {}", eventId, e.getMessage());
            throw new RuntimeException(e); // rollback -> останется PENDING
        }
    }
}