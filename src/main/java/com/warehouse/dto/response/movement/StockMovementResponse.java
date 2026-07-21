package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ответ с информацией о движении товара.
 * 
 * @param itemId ID товара
 * @param movementId ID записи движения
 * @param type Тип движения (приход/списание)
 * @param quantity Количество изменённых единиц
 * @param stockAfter Остаток после операции
 * @param createdAt Время операции
 * @param lowStockAlert true, если остаток опустился ниже минимального
 * @param warehouseId ID склада
 * @param warehouseName название склада
 * @param transferId ID перевода, если движение является частью перевода
 */
public record StockMovementResponse(
        Long itemId,
        Long movementId,
        MovementType type,
        int quantity,
        int stockAfter,
        LocalDateTime createdAt,
        boolean lowStockAlert,
        Long warehouseId,
        String warehouseName,
        UUID transferId
) {
    public StockMovementResponse(
            Long itemId,
            Long movementId,
            MovementType type,
            int quantity,
            int stockAfter,
            LocalDateTime createdAt,
            boolean lowStockAlert
    ) {
        this(itemId, movementId, type, quantity, stockAfter, createdAt, lowStockAlert, null, null, null);
    }
}
