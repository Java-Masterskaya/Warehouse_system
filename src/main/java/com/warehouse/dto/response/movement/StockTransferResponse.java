package com.warehouse.dto.response.movement;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Result of an atomic transfer between warehouses.
 *
 * @param transferId identifier shared by both movement records
 * @param itemId item identifier
 * @param fromWarehouseId source warehouse identifier
 * @param toWarehouseId destination warehouse identifier
 * @param quantity transferred quantity
 * @param fromStockAfter source stock after the transfer
 * @param toStockAfter destination stock after the transfer
 * @param outMovementId source movement identifier
 * @param inMovementId destination movement identifier
 * @param transferredAt transfer time
 */
public record StockTransferResponse(
        UUID transferId,
        Long itemId,
        Long fromWarehouseId,
        Long toWarehouseId,
        int quantity,
        int fromStockAfter,
        int toStockAfter,
        Long outMovementId,
        Long inMovementId,
        LocalDateTime transferredAt
) {
}
