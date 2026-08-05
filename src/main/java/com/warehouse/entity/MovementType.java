package com.warehouse.entity;

/**
 * Типы движения товара на складе.
 */
public enum MovementType {
    /** Приход товара. */
    RECEIVE,

    /** Списание товара. */
    WRITE_OFF,

    /** Списание товара из-за истечения срока годности. */
    EXPIRED,

    /** Корректировка остатков. */
    ADJUSTMENT,

    /** Перевод товара со склада-источника. */
    TRANSFER_OUT,

    /** Перевод товара на склад-получатель. */
    TRANSFER_IN
}
