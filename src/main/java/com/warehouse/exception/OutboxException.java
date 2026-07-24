package com.warehouse.exception;

/**
 * Исключение, возникающее при работе с outbox.
 */
public class OutboxException extends RuntimeException {
    public OutboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
