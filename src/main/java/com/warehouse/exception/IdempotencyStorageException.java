package com.warehouse.exception;

/**
 * Исключение при ошибках хранения или восстановления данных идемпотентности.
 * Возникает при проблемах с сериализацией/десериализацией ответов.
 */
public class IdempotencyStorageException extends RuntimeException {
    public IdempotencyStorageException(String message) {
        super(message);
    }

    public IdempotencyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
