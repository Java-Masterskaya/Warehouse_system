package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record ReceiveStockRequest(
        @NotNull Long itemId,
        @Positive int quantity,
        @NotNull @Future LocalDateTime expiryDate
) {
}
