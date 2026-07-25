package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request to transfer an item between two warehouses.
 *
 * @param itemId item identifier
 * @param fromWarehouseId source warehouse identifier
 * @param toWarehouseId destination warehouse identifier
 * @param quantity quantity to transfer
 */
public record TransferStockRequest(
        @NotNull @Positive Long itemId,
        @NotNull @Positive Long fromWarehouseId,
        @NotNull @Positive Long toWarehouseId,
        @NotNull @Positive Integer quantity
) {
}
