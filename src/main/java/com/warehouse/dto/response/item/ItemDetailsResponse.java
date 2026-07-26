package com.warehouse.dto.response.item;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    Long id;
    String sku;
    String name;
    String category;
    int minStock;
    long currentStock;
    BigDecimal price;
    BigDecimal cost;
    @JsonProperty("isActive") boolean active;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    long available;
    long reserved;
    List<WarehouseStockResponse> warehouseStocks;
}
