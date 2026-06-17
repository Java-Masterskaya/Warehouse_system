package com.warehouse.dto.event;

import java.time.LocalDateTime;

public record LowStockAlertEvent(
        Long itemId,
        String sku,
        String itemName,
        int currentStock,
        int minStock,
        String triggeredBy,
        LocalDateTime triggeredAt
) {
}