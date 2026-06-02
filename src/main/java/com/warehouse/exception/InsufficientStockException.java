package com.warehouse.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long itemId, int requested, int available) {
        super("Insufficient stock for item " + itemId + ": requested " + requested + ", available " + available);
    }
}
