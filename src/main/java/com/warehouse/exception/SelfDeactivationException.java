package com.warehouse.exception;

public class SelfDeactivationException extends RuntimeException {
    public SelfDeactivationException(String message) {
        super(message);
    }

    public static SelfDeactivationException forUser(Long userId) {
        return new SelfDeactivationException("User with id '" + userId + "' can't deactivate himself");
    }
}
