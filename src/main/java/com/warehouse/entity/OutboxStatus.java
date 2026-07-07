package com.warehouse.entity;

/**
 * Статус события в outbox.
 */
public enum OutboxStatus {
    /**
     * Событие ожидает отправки.
     */
    PENDING,
    
    /**
     * Событие успешно отправлено.
     */
    SENT,
    
    /**
     * Ошибка при отправке.
     */
    FAILED
}
