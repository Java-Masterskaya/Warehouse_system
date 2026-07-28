package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WriteOffStockRequest(
        @NotNull Long itemId,
        @Positive int quantity
) {
}
