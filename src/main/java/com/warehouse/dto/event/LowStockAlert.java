package com.warehouse.dto.event;

public record LowStockAlert(
        Long itemId,
        String itemName,
        Integer currentStock,
        Integer minStock
) {}