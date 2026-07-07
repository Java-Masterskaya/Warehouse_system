package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
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

        Stock stock = stockRepository.findByItemIdForUpdate(itemId).orElseThrow(() -> {
            log.warn("Stock not found: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        });

        int reservations = stockReserveRepository.findAllByStockAndStatus(stock, ReservationStatus.ACTIVE).stream()
                .mapToInt(Reservation::getQuantity).sum();

        log.info("Start reservation. Current reservations = {}", reservations);
        int available = stock.getQuantity() - reservations;
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
}
