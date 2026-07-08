package com.warehouse.dto.request.reservation;

import jakarta.validation.constraints.NotNull;

/**
 * Запрос на резервирование остатков.
 * @param reservationId id записи резерва
 */
public record ReservationActionRequest(
        @NotNull
        Long reservationId
) {}
