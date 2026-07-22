package com.warehouse.exception;

public class PurchaseOrderOverReceiptException extends RuntimeException {

    public PurchaseOrderOverReceiptException(String message) {
        super(message);
    }

    public static PurchaseOrderOverReceiptException forItem(
            Long purchaseOrderItemId,
            int orderedQty,
            int receivedQty,
            int requestedQty) {

        return new PurchaseOrderOverReceiptException(
                "Purchase order item with id '" + purchaseOrderItemId
                        + "' cannot receive " + requestedQty
                        + " items: orderedQty=" + orderedQty
                        + ", receivedQty=" + receivedQty
        );
    }
}
