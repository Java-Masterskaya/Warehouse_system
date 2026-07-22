package com.warehouse.dto.request.purchaseorder;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePurchaseOrderItemRequest(
        @NotNull Long itemId,
        @Min(1) int orderedQty
) {
}
