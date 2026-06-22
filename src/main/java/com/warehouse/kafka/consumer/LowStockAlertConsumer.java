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
     *
     * @param event событие с данными об остатке
     */
    @KafkaListener(topics = "low-stock-alerts", groupId = "warehouse-alerts")
    public void consume(LowStockAlertEvent event) {
        log.info("Received low stock alert for itemId={}, currentStock={}, minStock={}",
                event.itemId(), event.currentStock(), event.minStock());

        StockAlert alert = stockAlertMapper.toEntity(
                event,
                itemRepository.getReferenceById(event.itemId())
        );
        stockAlertRepository.save(alert);

        log.info("StockAlert saved with id={}", alert.getId());
    }
}
