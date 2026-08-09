package com.warehouse.exception;

public class IllegalFileImportException extends RuntimeException {
    public IllegalFileImportException(String message) {
        super(message);
    }
}
