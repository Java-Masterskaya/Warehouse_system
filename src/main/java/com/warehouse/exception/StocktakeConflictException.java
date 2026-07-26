package com.warehouse.exception;

public class StocktakeConflictException extends RuntimeException {
    public StocktakeConflictException(String message) {
        super(message);
    }

    public static StocktakeConflictException of(int counted, int reserved) {
        return new StocktakeConflictException(
                "Inventory result quantity=%d is lower than active reserved quantity=%d. "
                        + "Cannot update stock to inconsistent state.".formatted(counted, reserved));
    }
}
