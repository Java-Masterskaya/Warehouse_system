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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Потребитель событий о низком остатке товара.
 * Сохраняет каждый полученный алерт в базу данных.
 * Использует кастомный container factory с errorHandler и DLT.
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
     *
     * @param event событие с данными об остатке
     */
    @KafkaListener(topics = "low-stock-alerts", groupId = "warehouse-alerts",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(LowStockAlertEvent event) {
        log.info("Received low stock alert for itemId={}", event.itemId());

        var item = itemRepository.findById(event.itemId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Item not found: " + event.itemId()));

        try {
            StockAlert alert = stockAlertMapper.toEntity(event, item);
            stockAlertRepository.save(alert);
            log.info("StockAlert saved with id={}", alert.getId());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Duplicate alert skipped: itemId={}, triggeredAt={}",
                    event.itemId(), event.triggeredAt());
        }
    }
}
