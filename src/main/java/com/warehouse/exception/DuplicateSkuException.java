package com.warehouse.exception;

public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String sku) {
        super("Item with SKU '" + sku + "' already exists");
    }
}
