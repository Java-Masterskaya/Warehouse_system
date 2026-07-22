package com.warehouse.dto.response.report;

import java.time.LocalDateTime;

public record ExpiringBatch(
        Long id,
        String sku,
        String name,
        String category,
        int quantity,
        LocalDateTime expiryDate
) {}
