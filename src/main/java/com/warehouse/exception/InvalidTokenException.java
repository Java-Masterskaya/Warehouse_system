package com.warehouse.exception;

/**
 * Исключение, выбрасываемое при невалидном токене.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
