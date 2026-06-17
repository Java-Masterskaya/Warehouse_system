package com.warehouse.kafka.producer;

import com.warehouse.dto.event.LowStockAlertEvent;

public interface KafkaProducerService {

    /**
     * Отправляет уведомление о низком остатке товара в очередь сообщений.
     *
     * @param alert DTO с данными об остатке товара
     */
    void sendLowStockAlert(LowStockAlertEvent alert);
}