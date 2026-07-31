package com.warehouse.exception;

/**
 * Исключение, когда запрос с идемпотентным ключом уже выполняется.
 */
public class IdempotencyExecutionException extends RuntimeException {
    public IdempotencyExecutionException(String message) {
        super(message);
    }
}
