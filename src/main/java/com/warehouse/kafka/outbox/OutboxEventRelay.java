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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaStockAlertProducer kafkaProducer;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.outbox.polling.limit:100}")
    private int pollingLimit;

    @Value("${spring.kafka.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${spring.kafka.outbox.retry-backoff-ms:5000}")
    private long retryBackoffMs;

    @Scheduled(fixedDelayString = "${spring.kafka.outbox.polling.interval-ms:5000}")
    @Transactional
    public void relayPendingEvents() {
        log.debug("Checking for pending outbox events, limit={}", pollingLimit);

        // Сначала обрабатываем PENDING события
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(pollingLimit);
        List<OutboxEvent> failedEvents = outboxEventRepository.findFailedEventsForRetry(
                pollingLimit - pendingEvents.size());

        if (pendingEvents.isEmpty() && failedEvents.isEmpty()) {
            log.debug("No pending or failed outbox events found");
            return;
        }

        log.info("Found {} pending + {} failed events, processing...",
                pendingEvents.size(), failedEvents.size());

        for (OutboxEvent event : pendingEvents) {
            processSingleEvent(event);
        }

        for (OutboxEvent event : failedEvents) {
            processSingleEvent(event);
        }

        log.info("Processed {} events", pendingEvents.size() + failedEvents.size());
    }

    /**
     * Возвращает количество событий по статусу (для метрик).
     *
     * @param status статус
     * @return количество событий
     */
    public long countByStatus(com.warehouse.entity.OutboxStatus status) {
        return outboxEventRepository.countByStatus(status);
    }

    private void processSingleEvent(OutboxEvent event) {
        // Entity уже attached в текущей транзакции
        if (event.getStatus() != OutboxStatus.PENDING) {
            log.debug("Event id={} already processed (status={}), skipping",
                    event.getId(), event.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        
        try {
            LowStockAlertEvent alertEvent = objectMapper.readValue(
                    event.getPayload(),
                    LowStockAlertEvent.class
            );

            kafkaProducer.sendLowStockAlert(alertEvent);

            // Успешная отправка - помечаем как SENT
            outboxEventRepository.updateToSent(event.getId(), LocalDateTime.now());
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(LocalDateTime.now());

            log.debug("Successfully sent event id={} to Kafka", event.getId());
        } catch (Exception e) {
            log.error("Error processing outbox event id={}: {}", event.getId(), e.getMessage(), e);
            
            // Проверяем, достигнут ли лимит ретраев
            if (event.getRetryCount() + 1 >= maxRetries) {
                // Превышен лимит ретраев - помечаем как FAILED навсегда
                outboxEventRepository.updateToFailed(
                        event.getId(),
                        "Max retries exceeded (" + maxRetries + " attempts)",
                        event.getRetryCount() + 1,
                        now
                );
                log.warn("Event id={} marked as FAILED after {} retries", event.getId(), maxRetries);
            } else {
                // Ещё есть попытки - помечаем как FAILED для ретрая
                outboxEventRepository.updateToFailed(
                        event.getId(),
                        e.getMessage(),
                        event.getRetryCount() + 1,
                        now
                );
                log.debug("Event id={} marked as FAILED for retry {} of {}", 
                        event.getId(), event.getRetryCount() + 1, maxRetries);
            }
        }
    }
}