package com.warehouse.dto.response.item;

import io.swagger.v3.oas.annotations.media.Schema;

public record WarehouseStockResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long warehouseId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String warehouseName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long quantity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reserved,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long available
) {
}
