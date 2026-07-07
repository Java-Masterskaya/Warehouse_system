package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReleaseRequest;
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

        Stock stock = findStockWithLock(itemId);

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
        log.info("Order was reserved");
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public void release(Long itemId, ReleaseRequest request, UserContext ctx) {
        // Lock stock to serialize reservation modifications.
        Stock stock = findStockWithLock(itemId);

        Reservation reservation = stockReserveRepository.findById(request.reservationId())
                .orElseThrow(() -> EntityNotFoundException.forId("Reservation", request.reservationId()));
        if (!reservation.getStock().getItem().getId().equals(itemId)) {
            log.warn("The requested reservation does not match the product. Reservation is for itemId = {}, but "
                    + "current item has id = {}.", reservation.getStock().getItem().getId(), itemId);
            throw ReservationException.ofItem(reservation.getId(), itemId);
        }
        if (!reservation.getUser().getId().equals(ctx.userId())) {
            log.warn("This reservation belong to another user");
            throw ReservationException.ofUser(reservation.getId(), reservation.getUser().getId());
        }
        if (reservation.getStatus() == ReservationStatus.ACTIVE) {
            log.info("Release reservation {} for item {}", reservation.getId(), itemId);
            reservation.setStatus(ReservationStatus.CANCELED);
            stockReserveRepository.save(reservation);
        } else {
            log.warn("Reservation is not active. Current status is {}", reservation.getStatus());
            throw ReservationException.ofStatus(ReservationStatus.ACTIVE, reservation.getStatus());
        }
    }

    private Stock findStockWithLock(Long itemId) {
        return stockRepository.findByItemIdForUpdate(itemId).orElseThrow(() -> {
            log.warn("Stock not found: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        });
    }
}
