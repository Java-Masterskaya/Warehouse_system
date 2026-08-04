package com.warehouse.exception;

/**
 * Исключение для параллельного запроса ротации refresh-токена.
 */
public class RefreshInProgressException extends RuntimeException {

    public RefreshInProgressException(String message) {
        super(message);
    }
}
