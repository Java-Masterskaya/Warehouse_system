package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос на создание движения товара.
 * 
 * @param itemId ID товара
 * @param quantity Количество единиц (должно быть >= 1)
 */
public record CreateStockMovementRequest(
    @NotNull Long itemId,
    @Min(1) int quantity
) {}