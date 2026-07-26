package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.exception.OutboxException;
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
     *
     * @param event событие
     */
    protected void processSingleEvent(OutboxEvent event) {
        if (event.getStatus() != OutboxStatus.PENDING && event.getStatus() != OutboxStatus.FAILED) {
            log.debug("Event id={} already processed (status={}), skipping",
                    event.getId(), event.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        if (event.getStatus() == OutboxStatus.FAILED && event.getLastAttemptAt() != null
                && shouldSkipDueToBackoff(event, now)) {
            return;
        }

        try {
            LowStockAlertEvent alertEvent = objectMapper.readValue(
                    event.getPayload(),
                    LowStockAlertEvent.class
            );

            kafkaProducer.sendLowStockAlert(alertEvent);

            outboxEventRepository.updateToSent(event.getId(), LocalDateTime.now());
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(LocalDateTime.now());

            log.debug("Successfully sent event id={} to Kafka", event.getId());
        } catch (JsonProcessingException e) {
            handleDeserializationError(event, e, now);
        } catch (Exception e) {
            handleProcessingError(event, e, now);
        }
    }

    private boolean shouldSkipDueToBackoff(OutboxEvent event, LocalDateTime now) {
        long currentRetry = event.getRetryCount();
        long exponentialBackoff = retryBackoffMs * (long) Math.pow(2, currentRetry);
        long timeSinceLastAttempt = Duration.between(event.getLastAttemptAt(), now).toMillis();

        if (timeSinceLastAttempt < exponentialBackoff) {
            log.debug("Event id={} skipped due to exponential backoff ({}/{}, retry {} of {})",
                    event.getId(), timeSinceLastAttempt, exponentialBackoff,
                    event.getRetryCount(), maxRetries);
            return true;
        }
        return false;
    }

    private void handleDeserializationError(OutboxEvent event, JsonProcessingException e, LocalDateTime now) {
        log.warn("Deserialization error for event id={}: {}", event.getId(), e.getMessage());

        Long dltId = outboxEventRepository.insertIntoDlt(
                event.getId(),
                "Deserialization error: " + e.getMessage(),
                event.getRetryCount(),
                now
        );

        if (dltId != null) {
            moveEventToDlt(event, dltId);
        } else {
            log.error("Failed to insert event id={} into DLT", event.getId());
        }
    }

    private void handleProcessingError(OutboxEvent event, Exception e, LocalDateTime now) {
        log.error("Error processing outbox event id={}: {}", event.getId(), e.getMessage(), e);

        int newRetryCount = event.getRetryCount() + 1;

        if (newRetryCount >= maxRetries) {
            Long dltId = outboxEventRepository.insertIntoDlt(
                    event.getId(),
                    "Max retries exceeded (" + maxRetries + " attempts). Last error: " + e.getMessage(),
                    newRetryCount,
                    now
            );
            if (dltId != null) {
                moveEventToDlt(event, dltId);
            } else {
                log.error("Failed to insert event id={} into DLT", event.getId());
            }
        } else {
            outboxEventRepository.updateToFailed(
                    event.getId(),
                    e.getMessage(),
                    newRetryCount,
                    now
            );
            event.setStatus(OutboxStatus.FAILED);
            event.setRetryCount(newRetryCount);
            event.setLastAttemptAt(now);
            event.setErrorMessage(e.getMessage());
            log.debug("Event id={} marked as FAILED for retry {} of {}",
                    event.getId(), newRetryCount, maxRetries);
        }
    }

    private void moveEventToDlt(OutboxEvent event, Long dltId) {
        int deleted = outboxEventRepository.deleteFromOutbox(event.getId());
        event.setStatus(OutboxStatus.PERMANENT_FAILURE);

        if (deleted > 0) {
            log.warn("Event id={} moved to DLT (dlt_id={}), outbox row deleted",
                    event.getId(), dltId);
        } else {
            int updated = outboxEventRepository.updateToPermanentFailure(event.getId());
            if (updated > 0) {
                log.warn("Event id={} moved to DLT (dlt_id={}), status updated to PERMANENT_FAILURE in outbox",
                        event.getId(), dltId);
            } else {
                log.error("CRITICAL: Event id={} moved to DLT but failed to delete from outbox (deleted=0) "
                                + "and failed to update status to PERMANENT_FAILURE (updated=0). "
                                + "Event may be lost or duplicated!",
                        event.getId());
                throw new OutboxException("Failed to move event to DLT: "
                        + "deleteFromOutbox=0, updateToPermanentFailure=0", null);
            }
        }
    }
}
