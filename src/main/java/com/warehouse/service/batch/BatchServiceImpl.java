package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.StockRepository;
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

    @Override
    @Transactional
    public int writeOffByFEFO(Long itemId, int quantity, LocalDateTime now) {
        log.debug("FEFO write-off: itemId={}, quantity={}, now={}", itemId, quantity, now);

        // Получаем доступный остаток (без резервов) с блокировкой
        Optional<Integer> availableOpt = stockRepository.findAvailableQuantityForUpdate(itemId, now);
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

        // Получаем неистекшие партии, отсортированные по expiryDate ASC
        List<Batch> batches = batchRepository.findNonExpiredByItemIdOrderByExpiryDateAsc(itemId, now);

        // Списываем по очереди из каждой партии
        int remaining = quantity;
        for (Batch batch : batches) {
            if (remaining <= 0) {
                break;
            }

            int batchQty = batch.getQuantity();
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
        int newStockQuantity = stock.getQuantity() - actuallyWrittenOff;
        stock.setQuantity(newStockQuantity);
        stockRepository.save(stock); // @Version гарантирует атомарность

        log.info("FEFO write-off completed: itemId={}, requested={}, actuallyWrittenOff={}, newStockQuantity={}",
                itemId, quantity, actuallyWrittenOff, newStockQuantity);

        return newStockQuantity; // Возвращаем остаток после списания
    }
}
