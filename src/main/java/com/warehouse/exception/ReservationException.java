package com.warehouse.exception;

import com.warehouse.entity.ReservationStatus;

public class ReservationException extends RuntimeException {
    public ReservationException(String message) {
        super(message);
    }

    public static ReservationException ofStatus(ReservationStatus expected, ReservationStatus was) {
        return new ReservationException("Reservation status expected %s, but was %s".formatted(expected, was));
    }

    public static ReservationException ofItem(Long reservation, Long item) {
        return new ReservationException("Reservation %d does not belong to item %d".formatted(reservation, item));
    }

    public static ReservationException ofUser(Long reservation, Long user) {
        return new ReservationException("Reservation %d does not belong to user %d".formatted(reservation, user));
    }

}