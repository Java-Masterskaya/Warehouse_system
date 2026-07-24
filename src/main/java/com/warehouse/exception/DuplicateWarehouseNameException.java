package com.warehouse.exception;

public class DuplicateWarehouseNameException extends RuntimeException {

    public DuplicateWarehouseNameException(String message) {
        super(message);
    }

    public static DuplicateWarehouseNameException forName(String name) {
        return new DuplicateWarehouseNameException("Warehouse with name '" + name + "' already exists");
    }
}
