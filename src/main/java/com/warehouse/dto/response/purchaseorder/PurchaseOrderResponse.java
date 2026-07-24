package com.warehouse.dto.response.purchaseorder;

import com.warehouse.entity.PurchaseOrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id,
        Long supplierId,
        String supplierName,
        PurchaseOrderStatus status,
        List<PurchaseOrderItemResponse> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
