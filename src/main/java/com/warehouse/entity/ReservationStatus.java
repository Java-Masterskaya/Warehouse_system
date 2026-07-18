package com.warehouse.entity;

// Статус бронирования
public enum ReservationStatus {
//    актуальная бронь
    ACTIVE,
//    выкупленный резерв
    CONSUMED,
//    отмененное резервирование
    CANCELED,
//    протухший резерв
    EXPIRED
}
