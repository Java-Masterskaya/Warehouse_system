package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;

import java.time.LocalDateTime;
import java.util.List;

public interface BatchService {

    /**
     * Creates a batch and increases stock at the selected warehouse.
     *
     * @param item item being received
     * @param warehouse destination warehouse
     * @param quantity received quantity
     * @param expiryDate batch expiry date
     * @return persisted batch
     */
    Batch createBatchAndIncreaseStock(
            Item item,
            Warehouse warehouse,
            int quantity,
            LocalDateTime expiryDate
    );

    /**
     * Finds item batches at a warehouse in FEFO order.
     *
     * @param itemId item identifier
     * @param warehouseId warehouse identifier
     * @return ordered batches
     */
    List<Batch> findByItemAndWarehouseOrderByExpiryDate(Long itemId, Long warehouseId);

    /**
     * Writes off available quantity at a warehouse in FEFO order.
     *
     * @param itemId item identifier
     * @param warehouseId warehouse identifier
     * @param quantity quantity to write off
     * @param now operation time
     * @return stock quantity after write-off
     * @throws InsufficientStockException when available quantity is insufficient
     */
    int writeOffByFEFO(
            Long itemId,
            Long warehouseId,
            int quantity,
            LocalDateTime now
    ) throws InsufficientStockException;

    /**
     * Writes off reserved quantity without subtracting the same reservation twice.
     *
     * @param itemId item identifier
     * @param warehouseId warehouse identifier
     * @param quantity quantity to write off
     * @param now operation time
     * @return stock quantity after write-off
     * @throws InsufficientStockException when non-expired physical quantity is insufficient
     */
    int writeOffReservedByFEFO(
            Long itemId,
            Long warehouseId,
            int quantity,
            LocalDateTime now
    ) throws InsufficientStockException;

    /**
     * Очистить протухшие партии (списать их количество в Stock).
     * Атомарная операция: очищает партии и уменьшает stock.quantity.
     * Использует pessimistic locking для безопасности.
     *
     * @param now текущее время
     * @return количество очищенных партий
     */
    int clearExpiredBatches(LocalDateTime now);
}
