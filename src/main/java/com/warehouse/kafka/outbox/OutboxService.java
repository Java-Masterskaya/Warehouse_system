package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.event.OutboxLowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.exception.OutboxException;
import com.warehouse.repository.OutboxEventRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Сервис для работы с outbox.
 * Обеспечивает атомарное сохранение событий и отправку их в Kafka.
 * 
 * <p>Использование:</p>
 * <pre>{@code
 * // В транзакционном методе сохраняем событие в outbox
 * outboxService.saveLowStockAlertEvent(event);
 * // Далее событие будет отправлено асинхронно релаем
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OutboxService {

    OutboxEventRepository outboxEventRepository;
    ObjectMapper objectMapper;

    /**
     * Сохраняет событие о низком остатке в outbox.
     * Вызывается в той же транзакции, что и бизнес-данные (stock_movement).
     * Гарантирует атомарность: либо и движение, и событие сохраняются, либо ничего.
     *
     * @param event событие низкого остатка
     */
    @Transactional
    public void saveLowStockAlertEvent(LowStockAlertEvent event) {
        try {
            OutboxLowStockAlertEvent outboxEvent = new OutboxLowStockAlertEvent(
                    event.itemId(),
                    event.sku(),
                    event.itemName(),
                    event.currentStock(),
                    event.minStock(),
                    event.triggeredBy(),
                    event.triggeredAt()
            );

            String payload = objectMapper.writeValueAsString(outboxEvent);

            OutboxEvent entity = OutboxEvent.builder()
                    .eventType("LowStockAlert")
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

            outboxEventRepository.save(entity);

            log.debug("Saved LowStockAlert event to outbox: itemId={}, eventId={}",
                    event.itemId(), entity.getId());
        } catch (Exception e) {
            log.error("Failed to save LowStockAlert event to outbox", e);
            throw new OutboxException("Failed to save event to outbox", e);
        }
    }
}
