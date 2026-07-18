package com.warehouse.exception;

/**
 * Исключение, выбрасываемое при попытке повторного использования
 * уже ротированного refresh токена.
 */
public class TokenReuseException extends RuntimeException {
    public TokenReuseException(String message) {
        super(message);
    }
}
