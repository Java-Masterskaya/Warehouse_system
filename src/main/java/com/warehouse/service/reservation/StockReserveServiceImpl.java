package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.ReservationException;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockReserveServiceImpl implements StockReserveService {

    StockRepository stockRepository;
    UserRepository userRepository;
    StockReserveRepository stockReserveRepository;
    StockMovementService movementService;
    MetricService metricService;

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public void reserve(Long itemId, ReserveRequest request, UserContext ctx) {
        int quantity = request.quantity();

        if (quantity <= 0) {
            log.warn("Invalid quantity for reservation: itemId={}, quantity={}", itemId, quantity);
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }

        Stock stock = lockStock(itemId);

        long reservations = stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE);

        log.info("Start reservation. Current reservations = {}", reservations);
        int available = Math.toIntExact(stock.getQuantity() - reservations);
        if (available < quantity) {
            log.warn("Reservation quantity is more then now available: available = {}, quantity = {}", available,
                    quantity);
            throw InsufficientStockException.of(itemId, quantity, available);
        }

        User userRef = userRepository.getReferenceById(ctx.userId());

        stockReserveRepository.save(
                Reservation.builder().stock(stock).user(userRef).quantity(quantity).status(ReservationStatus.ACTIVE)
                        .expiredAt(LocalDateTime.now().plusDays(request.daysReserved())).build());

        metricService.increment("warehouse.reservation.reserve.total");
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public void release(Long itemId, ReservationActionRequest request, UserContext ctx) {
        // Lock stock to serialize reservation modifications.
        lockStock(itemId);
        Reservation reservation = getActiveReservation(request.reservationId(), itemId);
        updateReservationStatus(reservation, ReservationStatus.CANCELED);
        metricService.increment("warehouse.reservation.release.total");
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public void writeOff(Long itemId, ReservationActionRequest request, UserContext ctx) {
        // Lock stock to serialize reservation modifications.
        lockStock(itemId);

        Reservation reservation = getActiveReservation(request.reservationId(), itemId);
        movementService.writeOffReceipt(new ChangeQuantityMovementRequest(itemId, reservation.getQuantity()), ctx);
        updateReservationStatus(reservation, ReservationStatus.CONSUMED);
        metricService.increment("warehouse.reservation.writeOff.total");
    }

    private Stock lockStock(Long itemId) {
        return stockRepository.findByItemIdForUpdate(itemId).orElseThrow(() -> {
            log.warn("Stock not found: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        });
    }

    private void updateReservationStatus(Reservation reservation, ReservationStatus status) {

        log.info("Update status for reservation {} to {}", reservation.getId(), status);
        reservation.setStatus(status);
        stockReserveRepository.save(reservation);
    }

    private Reservation getActiveReservation(Long reservationId, Long itemId) {
        Reservation reservation = stockReserveRepository.findById(reservationId)
                .orElseThrow(() -> EntityNotFoundException.forId("Reservation", reservationId));

        //      check reservation belong to current item
        if (!reservation.getStock().getItem().getId().equals(itemId)) {
            log.warn("The requested reservation does not match the product. Reservation is for itemId = {}, but " +
                    "current item has id = {}.", reservation.getStock().getItem().getId(), itemId);
            throw ReservationException.ofItem(reservation.getId(), itemId);
        }
        //        check status
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            log.warn("Reservation is not active. Current status is {}", reservation.getStatus());
            throw ReservationException.ofStatus(ReservationStatus.ACTIVE, reservation.getStatus());
        }

        // check reservation expired
        if (reservation.getExpiredAt().isBefore(LocalDateTime.now())) {
            log.warn("Reservation expired but stay Active.");
            throw ReservationException.ofStatus(ReservationStatus.EXPIRED, ReservationStatus.ACTIVE);
        }

        return reservation;
    }
}
