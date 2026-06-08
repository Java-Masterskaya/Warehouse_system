package com.warehouse.kafka.producer;

import com.warehouse.dto.event.LowStockAlert;

public interface KafkaProducerService {

    /**
     * Отправляет уведомление о низком остатке товара в очередь сообщений.
     *
     * @param alert DTO с данными об остатке товара
     */
    void sendLowStockAlert(LowStockAlert alert);
}