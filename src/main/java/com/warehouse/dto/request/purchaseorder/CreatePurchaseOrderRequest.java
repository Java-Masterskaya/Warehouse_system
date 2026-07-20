package com.warehouse.dto.request.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePurchaseOrderRequest(
        @NotNull Long supplierId,
        @NotEmpty List<@Valid CreatePurchaseOrderItemRequest> items
) {
}
