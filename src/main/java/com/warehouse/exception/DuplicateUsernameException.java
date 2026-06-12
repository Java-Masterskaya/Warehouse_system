package com.warehouse.exception;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String message) {
        super(message);
    }

    public static DuplicateUsernameException forUsername(String username) {
        return new DuplicateUsernameException("Username '" + username + "' is already taken");
    }
}