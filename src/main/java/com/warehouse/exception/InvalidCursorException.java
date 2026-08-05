package com.warehouse.exception;

/**
 * Signals that a keyset cursor is malformed or does not belong to the request.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("Invalid cursor");
    }

    public InvalidCursorException(Throwable cause) {
        super("Invalid cursor", cause);
    }
}
