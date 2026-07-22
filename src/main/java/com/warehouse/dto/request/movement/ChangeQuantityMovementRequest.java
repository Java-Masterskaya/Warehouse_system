package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.FutureOrPresent;
import java.time.LocalDateTime;

/**
 * Запрос на создание движения товара.
 * 
 * @param itemId ID товара
 * @param quantity Количество единиц (должно быть >= 1)
 * @param expiryDate Срок годности партии (обязательное поле)
 */
public record ChangeQuantityMovementRequest(
    @NotNull Long itemId,
    @Min(1) int quantity,
    @NotNull @FutureOrPresent LocalDateTime expiryDate
) {}
