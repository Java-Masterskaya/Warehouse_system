package com.warehouse.exception;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }

    public static EntityNotFoundException forId(String entity, Long id) {
        return new EntityNotFoundException(entity + " with id " + id + " not found");
    }
}