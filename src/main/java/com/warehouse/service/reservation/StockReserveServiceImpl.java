package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.StockRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockReserveServiceImpl implements StockReserveService {

    StockRepository stockRepository;
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

        Stock stock = stockRepository.findByItemId(itemId).orElseThrow(() -> {
            log.warn("Stock not found: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        });

        log.info("Start reservation");
        int reservations = 0; //todo get reservations from db
        int available = stock.getQuantity() - reservations;
        if (available < quantity) {
            log.warn("Reservation quantity is more then now available: available = {}, quantity = {}", available,
                    quantity);
            throw new RuntimeException(); //todo custom exception 422
        }
        log.info("Order was reserved");
        //repository.save(
        // new Reservation(
        //  stockId, quantity, created_at, expires_at(create_at+10days), status.Active, user
        // )
        // )

//        metricService.increment("warehouse.reservation.reserve.total");
    }
}
