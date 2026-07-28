package com.warehouse.exception;

/**
 * Исключение при конфликте идемпотентного ключа с изменённым телом запроса.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }

    public static IdempotencyConflictException of(String endpoint) {
        return new IdempotencyConflictException(
                "Idempotency key is already used with different request body for endpoint '%s'"
                        .formatted(endpoint));
    }
}
