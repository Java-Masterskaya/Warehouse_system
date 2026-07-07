package com.warehouse.exception;

/**
 * Исключение при нарушении инварианта сущности StockMovement.
 * Сигнализирует о внутреннем баге или некорректном состоянии системы.
 */
public class StockMovementInvariantException extends RuntimeException {
    public StockMovementInvariantException(String message) {
        super(message);
    }
}
