package com.warehouse.exception;

public class LastAdminDeactivationException extends RuntimeException {

    public LastAdminDeactivationException(String message) {
        super(message);
    }

    public static LastAdminDeactivationException forUser(Long userId) {
        return new LastAdminDeactivationException(
                "User with id '" + userId + "' is the last active ADMIN and cannot be deactivated"
        );
    }
}
