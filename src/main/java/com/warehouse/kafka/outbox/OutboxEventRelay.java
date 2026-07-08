package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.repository.OutboxEventRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler для отправки событий из outbox в Kafka.
 * Регулярно опрашивает таблицу outbox и отправляет события с статусом PENDING.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OutboxEventRelay {

    OutboxEventRepository outboxEventRepository;
    KafkaStockAlertProducer kafkaProducer;
    ObjectMapper objectMapper;

    /**
     * Конфигурируемые параметры из application.yml (kafka.outbox.polling)
     */
    @Value("${spring.kafka.outbox.polling.limit:100}")
    private int pollingLimit;

    @Value("${spring.kafka.outbox.polling.interval-ms:5000}")
    private long pollingIntervalMs;

    /**
     * Регулярно опрашивает outbox и отправляет события в Kafka.
     * Выполняется каждые pollingIntervalMs миллисекунд.
     * Обрабатывает максимум pollingLimit событий за один вызов.
     *
     * <p>Если Kafka недоступна, события остаются в статусе PENDING
     * и будут повторены при следующей попытке.</p>
     */
    @Scheduled(fixedDelayString = "${spring.kafka.outbox.polling.interval-ms:5000}")
    @Transactional
    public void relayPendingEvents() {
        log.debug("Checking for pending outbox events, limit={}", pollingLimit);

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(pollingLimit);

        if (pendingEvents.isEmpty()) {
            log.debug("No pending outbox events found");
            return;
        }

        log.info("Found {} pending outbox events, processing...", pendingEvents.size());

        // Обрабатываем каждое событие в отдельной транзакции
        // Это гарантирует at-least-once доставку:
        // - Если отправка в Kafka успешна, статус обновляется на SENT
        // - Если краш после отправки но до обновления — событие повторится
        // - Если ошибка при отправке — событие останется PENDING и повторится
        for (OutboxEvent event : pendingEvents) {
            try {
                processEventInSeparateTransaction(event);
            } catch (Exception e) {
                log.error("Failed to process outbox event with id={}, eventType={}",
                        event.getId(), event.getEventType(), e);
                // Продолжаем обработку остальных событий
            }
        }

        log.info("Processed {} outbox events", pendingEvents.size());
    }

    /**
     * Обрабатывает одно событие в отдельной транзакции.
     * Это гарантирует at-least-once доставку без отката всей батчи при ошибке.
     *
     * @param event событие из outbox
     * @throws Exception если произошла ошибка при обработке
     */
    @Transactional
    public void processEventInSeparateTransaction(OutboxEvent event) throws Exception {
        log.debug("Processing outbox event id={}, eventType={}", event.getId(), event.getEventType());

        try {
            // Десериализуем событие
            LowStockAlertEvent lowStockAlertEvent = objectMapper.readValue(
                    event.getPayload(),
                    LowStockAlertEvent.class
            );

            // Отправляем в Kafka
            kafkaProducer.sendLowStockAlert(lowStockAlertEvent);

            // Обновляем статус на SENT
            int updated = outboxEventRepository.updateToSent(
                    event.getId(),
                    LocalDateTime.now()
            );

            if (updated == 0) {
                log.warn("Failed to update outbox event id={} to SENT status (may be concurrent update)", event.getId());
            } else {
                log.debug("Successfully sent event id={} to Kafka, updated status to SENT",
                        event.getId());
            }
        } catch (Exception e) {
            log.error("Error processing outbox event id={}: {}", event.getId(), e.getMessage());
            // Исключение будет перехвачено в relayPendingEvents, событие останется PENDING
            throw e;
        }
    }
}
