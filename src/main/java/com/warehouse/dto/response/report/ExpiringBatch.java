package com.warehouse.dto.response.report;

import java.time.LocalDateTime;

public record ExpiringBatch(
        Long id,
        String sku,
        String name,
        String category,
        Long warehouseId,
        String warehouseName,
        int quantity,
        LocalDateTime expiryDate
) {}
