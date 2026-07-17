package com.warehouse.service.reservation;

import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StockAvailabilityService {
    private final StockReserveRepository reservationRepository;
    private final StockRepository stockRepository;

    public int getAvailable(long itemId) {
        Stock stock = getStock(itemId);
        long reserved = reservationRepository.findActiveReserveSumByStock(stock, ReservationStatus.ACTIVE,
                LocalDateTime.now());

        return Math.toIntExact(stock.getQuantity() - reserved);
    }

    public int getReserved(long itemId) {
        Stock stock = getStock(itemId);
        return reservationRepository.findActiveReserveSumByStock(stock, ReservationStatus.ACTIVE, LocalDateTime.now());
    }

    private Stock getStock(long itemId) {
        return stockRepository.findByItemId(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock by item", itemId));
    }
}
