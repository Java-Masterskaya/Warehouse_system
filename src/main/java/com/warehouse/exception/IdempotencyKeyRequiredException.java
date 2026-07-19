package com.warehouse.exception;

/**
 * Исключение когда идемпотентный ключ обязателен, но не предоставлен.
 */
public class IdempotencyKeyRequiredException extends RuntimeException {
    public IdempotencyKeyRequiredException(String message) {
        super(message);
    }

    public static IdempotencyKeyRequiredException forEndpoint(String endpoint) {
        return new IdempotencyKeyRequiredException(
                "Idempotency-Key header is required for %s endpoint".formatted(endpoint));
    }
}
