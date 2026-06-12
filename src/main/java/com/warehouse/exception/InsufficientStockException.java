package com.warehouse.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }

    public static InsufficientStockException of(Long itemId, int requested, int available) {
        return new InsufficientStockException(
                "Insufficient stock for item " + itemId + ": requested " + requested + ", available " + available);
    }
}