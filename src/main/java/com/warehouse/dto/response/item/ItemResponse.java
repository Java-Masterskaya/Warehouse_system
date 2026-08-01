package com.warehouse.dto.response.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sku,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int minStock,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("isActive") boolean active,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String barcode;

) {}
