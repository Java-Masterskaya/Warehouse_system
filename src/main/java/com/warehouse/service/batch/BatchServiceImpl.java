package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BatchServiceImpl implements BatchService {

    BatchRepository batchRepository;
    StockRepository stockRepository;
    StockAvailabilityService availabilityService;

    @Override
    @Transactional
    public Batch createBatchAndIncreaseStock(
            Item item,
            Warehouse warehouse,
            int quantity,
            LocalDateTime expiryDate
    ) {
        validateBatchInput(item, warehouse, quantity, expiryDate);

        stockRepository.createEmptyStockIfAbsent(item.getId(), warehouse.getId());
        Stock stock = lockStock(item.getId(), warehouse.getId());

        Batch batch = Batch.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();
        Batch savedBatch = batchRepository.save(batch);

        stock.setQuantity(Math.addExact(stock.getQuantity(), quantity));
        stockRepository.save(stock);

        log.info(
                "Batch created: batchId={}, itemId={}, warehouseId={}, quantity={}, expiryDate={}",
                savedBatch.getId(),
                item.getId(),
                warehouse.getId(),
                quantity,
                expiryDate
        );
        return savedBatch;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findByItemAndWarehouseOrderByExpiryDate(Long itemId, Long warehouseId) {
        return batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(itemId, warehouseId);
    }

    @Override
    @Transactional
    public int writeOffByFEFO(Long itemId, Long warehouseId, int quantity, LocalDateTime now) {
        return writeOff(itemId, warehouseId, quantity, now, false);
    }

    @Override
    @Transactional
    public int writeOffReservedByFEFO(Long itemId, Long warehouseId, int quantity, LocalDateTime now) {
        return writeOff(itemId, warehouseId, quantity, now, true);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", allEntries = true)
    public int clearExpiredBatches(LocalDateTime now) {
        List<BatchScope> scopes = batchRepository.findExpiredScopesWithQuantity(now).stream()
                .map(scope -> new BatchScope(scope.getItemId(), scope.getWarehouseId()))
                .sorted(Comparator.comparing(BatchScope::warehouseId).thenComparing(BatchScope::itemId))
                .toList();

        int clearedBatches = 0;
        for (BatchScope scope : scopes) {
            Stock stock = lockStock(scope.itemId(), scope.warehouseId());
            List<Batch> expiredBatches = batchRepository.findExpiredByItemAndWarehouseForUpdate(
                    scope.itemId(),
                    scope.warehouseId(),
                    now
            );
            if (expiredBatches.isEmpty()) {
                continue;
            }

            int expiredQuantity = sumQuantity(expiredBatches);
            if (stock.getQuantity() < expiredQuantity) {
                throw new IllegalStateException(
                        "Batch quantity exceeds stock for item " + scope.itemId()
                                + " at warehouse " + scope.warehouseId()
                );
            }

            expiredBatches.forEach(batch -> batch.setQuantity(0));
            batchRepository.saveAll(expiredBatches);
            stock.setQuantity(stock.getQuantity() - expiredQuantity);
            stockRepository.save(stock);
            clearedBatches += expiredBatches.size();
        }

        return clearedBatches;
    }

    private int writeOff(
            Long itemId,
            Long warehouseId,
            int quantity,
            LocalDateTime now,
            boolean reservedWriteOff
    ) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        Stock stock = lockStock(itemId, warehouseId);
        List<Batch> batches = batchRepository.findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                itemId,
                warehouseId,
                now
        );

        int batchAvailable = sumQuantity(batches);
        int stockAvailable = stock.getQuantity();
        if (!reservedWriteOff) {
            stockAvailable = availabilityService.getAvailable(stock);
        }
        int available = Math.min(batchAvailable, stockAvailable);
        if (available < quantity) {
            throw InsufficientStockException.atWarehouse(itemId, warehouseId, quantity, available);
        }

        int remaining = quantity;
        for (Batch batch : batches) {
            if (remaining == 0) {
                break;
            }
            int writtenOff = Math.min(batch.getQuantity(), remaining);
            batch.setQuantity(batch.getQuantity() - writtenOff);
            remaining -= writtenOff;
        }

        batchRepository.saveAll(batches);
        stock.setQuantity(stock.getQuantity() - quantity);
        stockRepository.save(stock);

        return stock.getQuantity();
    }

    private Stock lockStock(Long itemId, Long warehouseId) {
        return stockRepository.findByItemIdAndWarehouseIdForUpdate(itemId, warehouseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Stock not found for item " + itemId + " at warehouse " + warehouseId
                ));
    }

    private int sumQuantity(List<Batch> batches) {
        return batches.stream()
                .mapToInt(Batch::getQuantity)
                .reduce(0, Math::addExact);
    }

    private void validateBatchInput(
            Item item,
            Warehouse warehouse,
            int quantity,
            LocalDateTime expiryDate
    ) {
        if (item == null || item.getId() == null) {
            throw new IllegalArgumentException("Item must be persisted");
        }
        if (warehouse == null || warehouse.getId() == null) {
            throw new IllegalArgumentException("Warehouse must be persisted");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (!expiryDate.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Expiry date must be in the future");
        }
    }

    private record BatchScope(Long itemId, Long warehouseId) {
    }
}
