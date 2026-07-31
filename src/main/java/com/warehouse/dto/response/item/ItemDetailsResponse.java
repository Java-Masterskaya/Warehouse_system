package com.warehouse.dto.response.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ItemDetailsResponse {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sku;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int minStock;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long currentStock;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("isActive") boolean active;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime updatedAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long available;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reserved;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<WarehouseStockResponse> warehouseStocks;
}
