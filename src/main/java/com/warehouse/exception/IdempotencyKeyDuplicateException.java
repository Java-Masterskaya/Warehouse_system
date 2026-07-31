package com.warehouse.exception;

public class IdempotencyKeyDuplicateException extends RuntimeException {
    public IdempotencyKeyDuplicateException(String message) {
        super(message);
    }

    public IdempotencyKeyDuplicateException(String message, Throwable cause) {
        super(message, cause);
    }
}
