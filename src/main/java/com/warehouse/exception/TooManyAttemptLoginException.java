package com.warehouse.exception;

import lombok.Getter;

@Getter
public class TooManyAttemptLoginException extends RuntimeException {
    private final long waitTime;

    public TooManyAttemptLoginException(String message, long waitTime) {
        super(message);
        this.waitTime = waitTime;
    }
}
