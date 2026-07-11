package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
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

import java.time.Duration;
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
                pollingLimit - pendingEvents.size(), maxRetries, retryBackoffMs);

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

    /**
     * Обрабатывает одно событие outbox.
     * Защищенный метод для возможности тестирования.
     *
     * @param event событие
     */
    protected void processSingleEvent(OutboxEvent event) {
        // Entity уже attached в текущей транзакции
        // Обрабатываем только PENDING и FAILED события
        if (event.getStatus() != OutboxStatus.PENDING && event.getStatus() != OutboxStatus.FAILED) {
            log.debug("Event id={} already processed (status={}), skipping",
                    event.getId(), event.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        
        // Проверяем backoff ДО попытки отправки
        if (event.getStatus() == OutboxStatus.FAILED && event.getLastAttemptAt() != null) {
            long timeSinceLastAttempt = Duration.between(event.getLastAttemptAt(), now).toMillis();
            if (timeSinceLastAttempt < retryBackoffMs) {
                log.debug("Event id={} skipped due to backoff ({}/{})",
                        event.getId(), timeSinceLastAttempt, retryBackoffMs);
                return;  // Пропускаем, пока не пройдёт backoff
            }
        }
        
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
        } catch (JsonProcessingException e) {
            // Deserialization errors (битый JSON) - немедленно в DLT без ретраев
            log.warn("Deserialization error for event id={}: {}", event.getId(), e.getMessage());
            
            // Для deserialization errors retry_count НЕ увеличиваем (оставляем как есть)
            // Считаем это permanent failure
            Long dltId = outboxEventRepository.insertIntoDlt(
                    event.getId(),
                    "Deserialization error: " + e.getMessage(),
                    event.getRetryCount(), // не увеличиваем!
                    now
            );
            
            if (dltId != null) {
                int deleted = outboxEventRepository.deleteFromOutbox(event.getId());
                if (deleted > 0) {
                    outboxEventRepository.updateToPermanentFailure(event.getId());
                    event.setStatus(OutboxStatus.PERMANENT_FAILURE);
                    log.warn("Event id={} moved to DLT due to deserialization error (dlt_id={})", 
                            event.getId(), dltId);
                } else {
                    log.error("Failed to delete event id={} from outbox", event.getId());
                }
            } else {
                log.error("Failed to insert event id={} into DLT", event.getId());
            }
        } catch (Exception e) {
            log.error("Error processing outbox event id={}: {}", event.getId(), e.getMessage(), e);
            
            int newRetryCount = event.getRetryCount() + 1;
            
            // Проверяем, достигнут ли лимит ретраев
            if (newRetryCount >= maxRetries) {
                // Превышен лимит ретраев - перемещаем в DLT
                Long dltId = outboxEventRepository.insertIntoDlt(
                        event.getId(),
                        "Max retries exceeded (" + maxRetries + " attempts). Last error: " + e.getMessage(),
                        newRetryCount,
                        now
                );
                
                if (dltId != null) {
                    int deleted = outboxEventRepository.deleteFromOutbox(event.getId());
                    if (deleted > 0) {
                        outboxEventRepository.updateToPermanentFailure(event.getId());
                        event.setStatus(OutboxStatus.PERMANENT_FAILURE);
                        log.warn("Event id={} moved to DLT after {} retries (dlt_id={})", 
                                event.getId(), maxRetries, dltId);
                    } else {
                        log.error("Failed to delete event id={} from outbox", event.getId());
                    }
                } else {
                    log.error("Failed to insert event id={} into DLT", event.getId());
                }
            } else {

                // Ещё есть попытки - помечаем как FAILED для ретрая
                outboxEventRepository.updateToFailed(
                        event.getId(),
                        e.getMessage(),
                        newRetryCount,
                        now
                );
                event.setStatus(OutboxStatus.FAILED);
                event.setRetryCount(newRetryCount);
                event.setLastAttemptAt(now);
                event.setErrorMessage(e.getMessage());  // БЫЛО: missing!
                log.debug("Event id={} marked as FAILED for retry {} of {}",
                        event.getId(), newRetryCount, maxRetries);
            }
        }
    }
}
