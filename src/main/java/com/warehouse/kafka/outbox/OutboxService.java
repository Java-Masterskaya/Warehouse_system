package com.warehouse.kafka.outbox;

import com.warehouse.dto.event.LowStockAlertEvent;

/**
 * Сервис для работы с outbox.
 * Обеспечивает атомарное сохранение событий и отправку их в Kafka.
 */
public interface OutboxService {

    /**
     * Сохраняет событие о низком остатке в outbox.
     * Вызывается в той же транзакции, что и бизнес-данные (stock_movement).
     * Гарантирует атомарность: либо и движение, и событие сохраняются, либо ничего.
     *
     * @param event событие низкого остатка
     */
    void saveLowStockAlertEvent(LowStockAlertEvent event);
}
