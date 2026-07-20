package com.warehouse.dto.request.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReceivePurchaseOrderRequest(
        @NotEmpty List<@Valid ReceivePurchaseOrderItemRequest> items
) {
}
