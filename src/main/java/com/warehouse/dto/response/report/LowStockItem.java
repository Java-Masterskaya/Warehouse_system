package com.warehouse.dto.response.report;

public record LowStockItem(
        Long id,
        String sku,
        String name,
        String category,
        int currentStock,
        int minStock,
        int deficit
) {}
