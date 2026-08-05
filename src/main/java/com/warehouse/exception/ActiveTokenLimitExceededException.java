package com.warehouse.exception;

public class ActiveTokenLimitExceededException extends RuntimeException {

    public ActiveTokenLimitExceededException(String message) {
        super(message);
    }
}
