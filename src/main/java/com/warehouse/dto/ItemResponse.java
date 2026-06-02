package com.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ItemResponse(
        Long id,
        String sku,
        String name,
        String category,
        int minStock,
        @JsonProperty("isActive") boolean active,
        LocalDateTime createdAt
) {}
