package com.warehouse.kafka.consumer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.StockAlert;
import com.warehouse.mapper.StockAlertMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Потребитель событий о низком остатке товара.
 * Сохраняет каждый полученный алерт в базу данных.
 * Реализует idempotent consumer через проверку существования записи.
 * 
 * <p>Для дедупликации используется уникальный индекс (item_id, created_at).
 * Сначала проверяем, существует ли запись. Если да — пропускаем без ошибки.
 * Это предотвращает попадание дубликатов в DLT и ретраи.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LowStockAlertConsumer {

    StockAlertRepository stockAlertRepository;
    StockAlertMapper stockAlertMapper;
    ItemRepository itemRepository;

    /**
     * Обрабатывает событие низкого остатка из топика Kafka.
     * Создаёт и сохраняет запись в таблице stock_alerts.
     * Использует проверку существования для idempotent consumer.
     *
     * @param event событие с данными об остатке
     */
    @KafkaListener(topics = "low-stock-alerts", groupId = "warehouse-alerts",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consume(LowStockAlertEvent event) {
        log.info("Received low stock alert for itemId={}, currentStock={}, minStock={}",
                event.itemId(), event.currentStock(), event.minStock());

        // 1. Проверяем, существует ли уже алерт для этого события
        // Используем бизнес-ключ: itemId + createdAt (из события)
        boolean exists = stockAlertRepository.existsByItemIdAndCreatedAt(
                event.itemId(), event.triggeredAt()
        );

        if (exists) {
            log.debug("Idempotency check passed. Duplicate detected. Skipping: itemId={}, triggeredAt={}",
                    event.itemId(), event.triggeredAt());
            return; // Это НЕ ошибка, это нормальный успех. Spring Kafka не будет делать ретрай и не отправит в DLT.
        }

        // 2. Если записи нет — выполняем бизнес-логику
        var item = itemRepository.findById(event.itemId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Item not found: " + event.itemId()));

        try {
            StockAlert alert = stockAlertMapper.toEntity(event, item);
            stockAlertRepository.save(alert);
            log.info("StockAlert saved with id={}", alert.getId());
        } catch (DataIntegrityViolationException e) {
            // На всякий случай логируем, если дубликат всё-же попал
            log.warn("Duplicate alert skipped (DataIntegrityViolationException): itemId={}, triggeredAt={}",
                    event.itemId(), event.triggeredAt());
        }
    }
}
