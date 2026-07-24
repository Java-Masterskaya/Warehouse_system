package com.warehouse.dto.response.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ItemDetailsResponse {
    Long id;
    String sku;
    String name;
    String category;
    int minStock;
    int currentStock;
    BigDecimal price;
    BigDecimal cost;
    @JsonProperty("isActive") boolean active;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    String barcode;
    int available;
    int reserved;
}