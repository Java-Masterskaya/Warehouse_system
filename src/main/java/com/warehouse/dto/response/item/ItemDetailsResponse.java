package com.warehouse.dto.response.item;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ItemDetailsResponse(
        Long id,
        String sku,
        String name,
        String category,
        int minStock,
        int currentStock,
        @JsonProperty("isActive") boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
