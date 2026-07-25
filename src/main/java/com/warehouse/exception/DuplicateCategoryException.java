package com.warehouse.exception;

public class DuplicateCategoryException extends RuntimeException {

    public DuplicateCategoryException(String message) {
        super(message);
    }

    public static DuplicateCategoryException forName(String name) {
        return new DuplicateCategoryException(
                "Category with name '" + name + "' already exists"
        );
    }
}
