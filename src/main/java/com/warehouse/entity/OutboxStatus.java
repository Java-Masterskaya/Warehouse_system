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
    FAILED,
    
    /**
     * Событие перемещено в DLT (Dead Letter Table) после превышения лимита ретраев.
     */
    PERMANENT_FAILURE
}
