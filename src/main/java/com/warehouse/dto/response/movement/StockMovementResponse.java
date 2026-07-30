package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ответ с информацией о движении товара.
 *
 * <p>Большинство полей, кроме {@code quantity}/{@code stockAfter}/{@code lowStockAlert},
 * могут быть {@code null} — см. {@code StockMovementMapper.toNoMovementResponse} (инвентаризация
 * без фактического движения) и обработку отсутствующих товара/партии в маппере.
 *
 * @param itemId ID товара
 * @param movementId ID записи движения
 * @param type Тип движения товара
 * @param quantity Количество изменённых единиц
 * @param stockAfter Остаток после операции
 * @param batchId ID партии (для поступлений)
 * @param expiryDate Срок годности партии (для поступлений)
 * @param createdAt Время операции
 * @param lowStockAlert true, если остаток опустился ниже минимального
 * @param warehouseId ID склада
 * @param warehouseName название склада
 * @param transferId ID перевода, если движение является частью перевода
 */
public record StockMovementResponse(
        @Schema(nullable = true) Long itemId,
        @Schema(nullable = true) Long movementId,
        @Schema(nullable = true) MovementType type,
        int quantity,
        int stockAfter,
        @Schema(nullable = true) Long batchId,
        @Schema(nullable = true) LocalDateTime expiryDate,
        @Schema(nullable = true) LocalDateTime createdAt,
        boolean lowStockAlert,
        @Schema(nullable = true) Long warehouseId,
        @Schema(nullable = true) String warehouseName,
        @Schema(nullable = true) UUID transferId
) {
}
