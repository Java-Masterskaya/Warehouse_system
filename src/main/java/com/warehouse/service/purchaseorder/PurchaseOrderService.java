package com.warehouse.service.purchaseorder;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.purchaseorder.PurchaseOrderResponse;

public interface PurchaseOrderService {

    PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request);

    PurchaseOrderResponse placePurchaseOrder(Long purchaseOrderId);

    PurchaseOrderResponse receivePurchaseOrder(
            Long purchaseOrderId,
            ReceivePurchaseOrderRequest request,
            UserContext context
    );

    PurchaseOrderResponse getPurchaseOrder(Long purchaseOrderId);

    PageResponse<PurchaseOrderResponse> getPurchaseOrders(int page, int size);
}
