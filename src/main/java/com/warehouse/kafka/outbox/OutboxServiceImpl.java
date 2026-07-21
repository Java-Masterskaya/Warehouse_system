package com.warehouse.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.event.OutboxLowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.exception.OutboxException;
import com.warehouse.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Реализация сервиса для работы с outbox.
 * Обеспечивает атомарное сохранение событий и отправку их в Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Override
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
