package com.warehouse.entity;

/**
 * Типы движения товара на складе.
 */
public enum MovementType {
    /** Приход товара. */
    RECEIVE,

    /** Списание товара. */
    WRITE_OFF,

    /** Корректировка остатков. */
    ADJUSTMENT,

    /** Списание просроченных партий. */
    EXPIRED
}
