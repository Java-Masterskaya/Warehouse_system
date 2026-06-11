package com.warehouse.dto.response.report;

public record LowStockItemResponse(
        Long id,
        String sku,
        String name,
        String category,
        int currentStock,
        int minStock,
        int deficit
) {}
