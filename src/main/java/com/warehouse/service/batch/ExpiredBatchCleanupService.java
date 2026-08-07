package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cleans one item and warehouse scope in its own transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpiredBatchCleanupService {

    private final BatchRepository batchRepository;
    private final StockRepository stockRepository;
    private final StockReserveRepository stockReserveRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;

    /**
     * Clears expired batches and records one aggregate audit movement.
     *
     * @param itemId item identifier
     * @param warehouseId warehouse identifier
     * @param actorId dynamically resolved system actor identifier
     * @param now cleanup timestamp
     * @return number of cleared batches
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int clearScope(Long itemId, Long warehouseId, Long actorId, LocalDateTime now) {
        Stock stock = lockStock(itemId, warehouseId);
        List<Batch> expiredBatches = batchRepository.findExpiredByItemAndWarehouseForUpdate(
                itemId,
                warehouseId,
                now
        );
        if (expiredBatches.isEmpty()) {
            return 0;
        }

        int expiredQuantity = sumQuantity(expiredBatches);
        if (stock.getQuantity() < expiredQuantity) {
            throw new IllegalStateException(
                    "Batch quantity exceeds stock for item " + itemId
                            + " at warehouse " + warehouseId
            );
        }

        int remainingStockQuantity = stock.getQuantity() - expiredQuantity;
        List<Reservation> canceledReservations = reconcileActiveReservations(stock, remainingStockQuantity, now);

        expiredBatches.forEach(batch -> batch.setQuantity(0));
        batchRepository.saveAll(expiredBatches);

        stock.setQuantity(remainingStockQuantity);
        stockRepository.save(stock);

        User actor = userRepository.getReferenceById(actorId);
        StockMovement movement = StockMovement.builder()
                .item(stock.getItem())
                .warehouse(stock.getWarehouse())
                .user(actor)
                .type(MovementType.EXPIRED)
                .quantity(expiredQuantity)
                .createdAt(now)
                .build();
        stockMovementRepository.saveAndFlush(movement);

        logCanceledReservations(itemId, warehouseId, canceledReservations, remainingStockQuantity);
        log.info(
                "Expired batches cleared: itemId={}, warehouseId={}, batches={}, quantity={}, movementId={}",
                itemId,
                warehouseId,
                expiredBatches.size(),
                expiredQuantity,
                movement.getId()
        );
        return expiredBatches.size();
    }

    private Stock lockStock(Long itemId, Long warehouseId) {
        return stockRepository.findByItemIdAndWarehouseIdForUpdate(itemId, warehouseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Stock not found for item " + itemId + " at warehouse " + warehouseId
                ));
    }

    /**
     * Preserves FIFO allocation by retaining older reservations and canceling whole newer reservations first.
     * Reservations are indivisible, so a whole cancellation may release more than the exact stock shortage.
     *
     * @param stock locked stock scope
     * @param remainingStockQuantity physical quantity after batch expiration
     * @param now cleanup timestamp
     * @return whole reservations canceled from newest to oldest
     */
    private List<Reservation> reconcileActiveReservations(
            Stock stock,
            int remainingStockQuantity,
            LocalDateTime now
    ) {
        List<Reservation> activeReservations = stockReserveRepository.findActiveByStockForUpdate(stock, now);
        long activeReservedQuantity = activeReservations.stream()
                .mapToLong(Reservation::getQuantity)
                .reduce(0L, Math::addExact);
        if (activeReservedQuantity <= remainingStockQuantity) {
            return List.of();
        }

        List<Reservation> canceledReservations = new ArrayList<>();
        for (Reservation reservation : activeReservations) {
            reservation.setStatus(ReservationStatus.CANCELED);
            canceledReservations.add(reservation);
            activeReservedQuantity -= reservation.getQuantity();
            if (activeReservedQuantity <= remainingStockQuantity) {
                break;
            }
        }
        stockReserveRepository.saveAll(canceledReservations);
        return List.copyOf(canceledReservations);
    }

    private void logCanceledReservations(
            Long itemId,
            Long warehouseId,
            List<Reservation> canceledReservations,
            int remainingStockQuantity
    ) {
        if (canceledReservations.isEmpty()) {
            return;
        }

        List<Long> reservationIds = canceledReservations.stream()
                .map(Reservation::getId)
                .toList();
        long canceledQuantity = canceledReservations.stream()
                .mapToLong(Reservation::getQuantity)
                .reduce(0L, Math::addExact);
        Runnable emitLog = () -> log.atWarn()
                .addKeyValue("itemId", itemId)
                .addKeyValue("warehouseId", warehouseId)
                .addKeyValue("reservationIds", reservationIds)
                .addKeyValue("canceledQuantity", canceledQuantity)
                .addKeyValue("remainingStockQuantity", remainingStockQuantity)
                .log(
                        "Reservations canceled during expired batch cleanup: itemId={}, warehouseId={}, "
                                + "reservationIds={}, canceledQuantity={}, remainingStockQuantity={}",
                        itemId,
                        warehouseId,
                        reservationIds,
                        canceledQuantity,
                        remainingStockQuantity
                );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emitLog.run();
                }
            });
            return;
        }
        emitLog.run();
    }

    private int sumQuantity(List<Batch> batches) {
        return batches.stream()
                .mapToInt(Batch::getQuantity)
                .reduce(0, Math::addExact);
    }
}
