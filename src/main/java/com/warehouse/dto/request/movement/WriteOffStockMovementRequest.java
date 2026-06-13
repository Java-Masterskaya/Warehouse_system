package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WriteOffStockMovementRequest(
        @NotNull Long itemId,
        @Min(1) @NotNull int quantity
) {}