package com.warehouse.dto.response.purchaseorder;

import java.math.BigDecimal;

public record PurchaseOrderItemResponse(
        Long id,
        Long itemId,
        String itemName,
        int orderedQty,
        int receivedQty,
        BigDecimal unitPrice,
        BigDecimal unitCost
) {
}
