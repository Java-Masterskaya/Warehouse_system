package com.warehouse.exception;

public class ImportHeadersException extends RuntimeException {
    public ImportHeadersException(String message) {
        super(message);
    }

    public static ImportHeadersException ofHeaders() {
        return new ImportHeadersException("Invalid headers. Expected:  Sku, Name, Category, Price, Cost.");
    }
}
