package com.warehouse.kafka.consumer;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.StockAlert;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.StockAlertMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * Потребитель событий о низком остатке товара.
 * Сохраняет каждый полученный алерт в базу данных.
 * Использует кастомный container factory с errorHandler и DLT.
 * Дубликаты (уникальный индекс) пропускаются, другие ошибки - в errorHandler.
 *
 * Важно: propagation = REQUIRES_NEW предотвращает проблему с rollback-only транзакцией.
 * Когда дубликат обнаружен (DataIntegrityViolationException), Spring помечает транзакцию как rollback-only.
 * Если не указать propagation = REQUIRES_NEW, транзакция внешнего вызова также станет rollback-only,
 * что приведёт к UnexpectedRollbackException и повторной доставке сообщения.
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
     * Использует container factory с errorHandler и DLT.
     * Дубликаты (уникальный индекс) пропускаются, другие ошибки - в errorHandler.
     *
     * @param event событие с данными об остатке
     */
    @KafkaListener(topics = "low-stock-alerts", groupId = "warehouse-alerts",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void consume(LowStockAlertEvent event) {
        log.info("Received low stock alert for itemId={}", event.itemId());

        var item = itemRepository.findById(event.itemId())
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + event.itemId()));

        int inserted = stockAlertRepository.insertIgnore(
                event.itemId(),
                event.currentStock(),
                event.minStock(),
                event.triggeredBy(),
                event.triggeredAt()
        );

        if (inserted == 1) {
            log.info("StockAlert saved for itemId={}", event.itemId());
        } else {
            log.debug("Duplicate alert skipped: itemId={}, triggeredAt={}",
                    event.itemId(), event.triggeredAt());
        }
    }
}
