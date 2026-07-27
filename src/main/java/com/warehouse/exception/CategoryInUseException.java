package com.warehouse.exception;

public class CategoryInUseException extends RuntimeException {

    public CategoryInUseException(String message) {
        super(message);
    }

    public static CategoryInUseException forId(Long categoryId) {
        return new CategoryInUseException(
                "Category with id " + categoryId + " is used by items"
        );
    }
}
