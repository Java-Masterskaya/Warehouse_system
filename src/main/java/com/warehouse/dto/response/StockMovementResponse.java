package com.warehouse.dto.response;

import com.warehouse.entity.MovementType;

import java.time.LocalDateTime;

/**
 * Ответ с информацией о движении товара.
 * 
 * @param itemId ID товара
 * @param movementId ID записи движения
 * @param type Тип движения (приход/списание)
 * @param quantity Количество изменённых единиц
 * @param stockAfter Остаток после операции
 * @param createdAt Время операции
 */
public record StockMovementResponse(
    Long itemId,
    Long movementId,
    MovementType type,
    int quantity,
    int stockAfter,
    LocalDateTime createdAt
) {}