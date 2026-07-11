package com.warehouse.dto.response.purchaseorder;

public record PurchaseOrderItemResponse(
        Long id,
        Long itemId,
        String itemName,
        int orderedQty,
        int receivedQty
) {
}
