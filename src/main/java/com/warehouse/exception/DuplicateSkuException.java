package com.warehouse.exception;

public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String message) {
        super(message);
    }

    public static DuplicateSkuException forSku(String sku) {
        return new DuplicateSkuException("Item with SKU '" + sku + "' already exists");
    }
}