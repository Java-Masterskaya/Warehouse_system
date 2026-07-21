package com.warehouse.dto.response.supplier;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record SupplierResponse(
        Long id,
        String name,
        @JsonProperty("isActive") boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
