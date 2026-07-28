package com.warehouse.dto.request.purchaseorder;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record ReceivePurchaseOrderItemRequest(
        @NotNull Long purchaseOrderItemId,
        @Min(1) int quantity,
        @NotNull @Future LocalDateTime expiryDate
) {
}
