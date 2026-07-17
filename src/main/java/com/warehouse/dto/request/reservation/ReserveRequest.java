package com.warehouse.dto.request.reservation;

import jakarta.validation.constraints.Min;

/**
 * Запрос на резервирование остатков.
 * @param quantity Количество единиц (должно быть >= 1)
 * @param daysReserved количество дней, на протяжении которых товар будет зарезервирован (должно быть >= 1)
 */
public record ReserveRequest(
        @Min(1)
        int quantity,
        @Min(1)
        int daysReserved
) {}
