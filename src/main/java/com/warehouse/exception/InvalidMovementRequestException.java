package com.warehouse.exception;

/**
 * Исключение для клиентских ошибок валидации количества или параметров движения товара.
 */
public class InvalidMovementRequestException extends RuntimeException {
    public InvalidMovementRequestException(String message) {
        super(message);
    }
}
