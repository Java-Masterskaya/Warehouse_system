package com.warehouse.dto.request.purchaseorder;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReceivePurchaseOrderItemRequest(
        @NotNull Long purchaseOrderItemId,
        @Min(1) int quantity
) {
}
