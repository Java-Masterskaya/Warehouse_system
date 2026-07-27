package com.warehouse.dto.response.item;

public record WarehouseStockResponse(
        Long warehouseId,
        String warehouseName,
        long quantity,
        long reserved,
        long available
) {
}
