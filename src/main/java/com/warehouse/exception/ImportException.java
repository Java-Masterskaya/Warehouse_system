package com.warehouse.exception;

public class ImportException extends RuntimeException {
    public ImportException(String message) {
        super(message);
    }

    public static ImportException ofHeaders() {
        return new ImportException("Invalid headers. Expected:  Sku, Name, Category, Price, Cost.");
    }
}
