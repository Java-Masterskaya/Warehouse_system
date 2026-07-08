package com.warehouse.dto.response.reservation;

import com.warehouse.entity.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationResponse(

        Long id,

        Long itemId,

        int quantity,

        Long userId,

        LocalDateTime reservedAt,

        LocalDateTime expiredAt,

        ReservationStatus status
) {
}
