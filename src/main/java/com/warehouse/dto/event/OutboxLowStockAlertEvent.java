package com.warehouse.dto.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.time.LocalDateTime;

/**
 * DTO для сериализации/десериализации события LowStockAlert в/out JSON.
 * Используется для хранения в outbox и отправки в Kafka.
 */
public record OutboxLowStockAlertEvent(
        Long itemId,
        String sku,
        String itemName,
        int currentStock,
        int minStock,
        String triggeredBy,
        
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        LocalDateTime triggeredAt
) {
}
