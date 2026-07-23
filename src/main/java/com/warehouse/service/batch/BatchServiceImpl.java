package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BatchServiceImpl implements BatchService {

    BatchRepository batchRepository;
    StockRepository stockRepository;
    WarehouseRepository warehouseRepository;

    @Override
    @Transactional
    public Batch createBatch(Item item, int quantity, LocalDateTime expiryDate) {
        log.debug("Creating batch for itemId={}, quantity={}, expiryDate={}", item.getId(), quantity, expiryDate);

        Batch batch = Batch.builder()
                .item(item)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();

        Batch saved = batchRepository.save(batch);
        log.info("Batch created: id={}, itemId={}, quantity={}, expiryDate={}",
                saved.getId(), saved.getItem().getId(), saved.getQuantity(), saved.getExpiryDate());

        return saved;
    }

    @Override
    @Transactional
    public Batch createBatchAndIncreaseStock(Item item, int quantity, LocalDateTime expiryDate) {
        log.debug("Creating batch and increasing stock for itemId={}, quantity={}, expiryDate={}",
                item.getId(), quantity, expiryDate);

        // Получаем default warehouse для логирования (не сохраняем в batch)
        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new EntityNotFoundException("Default warehouse not found"));

        Batch batch = Batch.builder()
                .item(item)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();

        Batch saved = batchRepository.save(batch);
        log.info("Batch created: id={}, itemId={}, quantity={}, expiryDate={}",
                saved.getId(), saved.getItem().getId(), saved.getQuantity(), saved.getExpiryDate());

        // Атомарно создаем stock для default warehouse, если его нет, и увеличиваем quantity
        Long warehouseId = defaultWarehouse.getId();
        log.debug("Creating empty stock if absent: itemId={}, warehouseId={}", item.getId(), warehouseId);
        int createdRows = stockRepository.createEmptyStockIfAbsent(item.getId(), warehouseId);
        log.debug("createEmptyStockIfAbsent result: createdRows={}", createdRows);
        
        int updatedRows = stockRepository.increaseQuantity(item.getId(), quantity);
        log.debug("increaseQuantity result: updatedRows={}", updatedRows);
        if (updatedRows == 0) {
            log.error("Stock not found for itemId={} after batch creation. This should not happen.", item.getId());
            throw new IllegalStateException("Stock not found after batch creation");
        }

        log.info("Batch created and stock increased: itemId={}, batchId={}, quantity={}",
                item.getId(), saved.getId(), quantity);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findByItemIdOrderByExpiryDate(Long itemId) {
        log.debug("Finding batches for itemId={}, ordered by expiryDate ASC", itemId);
        return batchRepository.findByItemIdOrderByExpiryDateAsc(itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Batch> findById(Long id) {
        return batchRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findAllWithItemByItemId(Long itemId) {
        log.debug("Finding all batches with item for itemId={}", itemId);
        return batchRepository.findAllWithItemByItemId(itemId);
    }

    /**
     * Списание товара по алгоритму FEFO (First-Expire-First-Out).
     * Гасим из партии с ближайшим сроком, при нехватке — добираем из следующих.
     * Одно списание может затронуть несколько партий.
     *
     * @param itemId      ID товара
     * @param quantity    количество для списания
     * @param now         текущее время (для проверки срока годности)
     * @return остаток после списания (newStockQuantity)
     * @throws InsufficientStockException если недостаточно товара во всех неистекших партиях
     */
    @Override
    @Transactional
    public int writeOffByFEFO(Long itemId, int quantity, LocalDateTime now) {
        log.debug("FEFO write-off: itemId={}, quantity={}, now={}", itemId, quantity, now);

        // Получаем доступный остаток из партий (без резервов и просроченных) с блокировкой
        Optional<Integer> availableOpt = stockRepository.findAvailableQuantityFromBatchesForUpdate(itemId, now);
        if (availableOpt.isEmpty()) {
            throw EntityNotFoundException.forId("Stock", itemId);
        }
        
        int available = availableOpt.get();
        if (available < quantity) {
            log.warn("Insufficient stock for FEFO write-off: itemId={}, requested={}, available={}",
                    itemId, quantity, available);
            throw new com.warehouse.exception.InsufficientStockException(
                    "Insufficient stock for FEFO write-off: requested " + quantity + ", available " + available);
        }

        // Сначала блокируем Stock, чтобы избежать deadlock
        Stock stock = stockRepository.findByItemIdForUpdate(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock", itemId));

        // Получаем неистекшие партии с блокировкой для обновления
        List<Batch> batches = batchRepository.findNonExpiredByItemIdOrderByExpiryDateAscForUpdate(itemId, now);

        // Списываем по очереди из каждой партии с блокировкой
        int remaining = quantity;
        for (Batch batch : batches) {
            if (remaining <= 0) {
                break;
            }

            // Проверяем остаток в партии (может измениться после блокировки)
            int batchQty = batch.getQuantity();
            if (batchQty <= 0) {
                continue;
            }

            if (batchQty <= remaining) {
                // Списываем всю партию
                log.debug("Writing off entire batch: id={}, quantity={}, remaining={}",
                        batch.getId(), batchQty, remaining);
                remaining -= batchQty;
                batch.setQuantity(0);
            } else {
                // Списываем часть партии
                log.debug("Writing off partial batch: id={}, batchQty={}, writeOff={}, remaining={}",
                        batch.getId(), batchQty, remaining, 0);
                batch.setQuantity(batchQty - remaining);
                remaining = 0;
            }
            batchRepository.save(batch); // @Version гарантирует атомарность для каждой партии
        }

        // Уменьшаем общий остаток на фактически списанное количество
        int actuallyWrittenOff = quantity - remaining;
        
        // Обновляем stock.quantity для default warehouse через decreaseQuantityIfEnough()
        // Это гарантирует атомарность и проверку остатка
        int updatedRows = stockRepository.decreaseQuantityIfEnough(itemId, actuallyWrittenOff);
        if (updatedRows == 0) {
            log.error("FEFO write-off failed: stock quantity not updated. This should not happen after successful write-off from batches.");
            throw new IllegalStateException("Stock quantity update failed after successful batch write-off");
        }
        
        // Получаем обновленное значение stock.quantity для default warehouse
        int newStockQuantity = stockRepository.findQuantityByItemId(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock", itemId));
        
        log.info("FEFO write-off completed: itemId={}, requested={}, actuallyWrittenOff={}, newStockQuantity={}",
                itemId, quantity, actuallyWrittenOff, newStockQuantity);

        return newStockQuantity; // Возвращаем остаток после списания
    }

    @Override
    @Transactional
    public int clearExpiredBatches(LocalDateTime now) {
        log.debug("Clearing expired batches: now={}", now);

        // Получаем все просроченные партии
        List<Batch> expiredBatches = batchRepository.findExpiredWithQuantity(now);
        if (expiredBatches.isEmpty()) {
            log.info("No expired batches to clear");
            return 0;
        }

        // Собираем данные по товарам (группируем по itemId)
        var batchesByItem = expiredBatches.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        batch -> batch.getItem().getId(),
                        java.util.stream.Collectors.summingInt(Batch::getQuantity)
                ));

        int totalCleared = 0;

        for (var entry : batchesByItem.entrySet()) {
            Long itemId = entry.getKey();
            int totalQty = entry.getValue();

            try {
                log.debug("Clearing expired batches for itemId={}, totalQuantity={}", itemId, totalQty);

                // Блокируем stock с pessimistic locking
                Stock stock = stockRepository.findByItemIdForUpdate(itemId)
                        .orElseThrow(() -> EntityNotFoundException.forId("Stock", itemId));

                // Проверяем, что есть достаточно остатка
                if (stock.getQuantity() < totalQty) {
                    log.warn("Insufficient stock for expired batch cleanup: itemId={}, stock={}, requested={}",
                            itemId, stock.getQuantity(), totalQty);
                    continue;
                }

                // Атомарно очищаем все просроченные партии для этого товара с блокировкой
                int clearedCount = batchRepository.clearExpiredBatchesByItemId(itemId, now);
                totalCleared += clearedCount;
                
                // Уменьшаем stock.quantity для default warehouse через decreaseQuantityIfEnough()
                // Это гарантирует атомарность и проверку остатка
                int updatedRows = stockRepository.decreaseQuantityIfEnough(itemId, totalQty);
                if (updatedRows == 0) {
                    log.error("Expired batch cleanup failed: stock quantity not updated. This should not happen after successful batch clearing.");
                    throw new IllegalStateException("Stock quantity update failed after clearing expired batches");
                }

                log.info("Expired batches cleared for itemId={}, count={}, totalQuantity={}",
                        itemId, clearedCount, totalQty);

            } catch (Exception e) {
                log.error("Error clearing expired batches for itemId={}", itemId, e);
                // Продолжаем обработку других товаров
            }
        }

        log.info("Clear expired batches completed: cleared={}", totalCleared);

        return totalCleared;
    }
}
