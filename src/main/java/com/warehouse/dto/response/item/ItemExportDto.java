package com.warehouse.dto.response.item;

import java.math.BigDecimal;

public record ItemExportDto(
        String sku,
        String name,
        String category,
        Long quantity,
        BigDecimal price
) {}
