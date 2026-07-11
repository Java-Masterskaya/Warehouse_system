package com.warehouse.service.purchaseorder;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.dto.response.purchaseorder.PurchaseOrderResponse;

import java.util.List;

public interface PurchaseOrderService {

    PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request);

    PurchaseOrderResponse placePurchaseOrder(Long purchaseOrderId);

    PurchaseOrderResponse receivePurchaseOrder(
            Long purchaseOrderId,
            ReceivePurchaseOrderRequest request,
            UserContext context
    );

    PurchaseOrderResponse getPurchaseOrder(Long purchaseOrderId);

    List<PurchaseOrderResponse> getPurchaseOrders();
}
