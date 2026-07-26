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
        return getAvailable(getDefaultStock(itemId));
    }

    public int getReserved(long itemId) {
        return getReserved(getDefaultStock(itemId));
    }

    public int getAvailable(Stock stock) {
        return stock.getQuantity() - getReserved(stock);
    }

    public int getReserved(Stock stock) {
        return reservationRepository.findActiveReserveSumByStock(
                stock,
                ReservationStatus.ACTIVE,
                LocalDateTime.now()
        );
    }

    public long getTotalQuantity(long itemId) {
        return stockRepository.findTotalQuantityByItemId(itemId);
    }

    public long getTotalReserved(long itemId) {
        return reservationRepository.findActiveReserveSumByItemId(
                itemId,
                ReservationStatus.ACTIVE,
                LocalDateTime.now()
        );
    }

    public long getTotalAvailable(long itemId) {
        return getTotalQuantity(itemId) - getTotalReserved(itemId);
    }

    private Stock getDefaultStock(long itemId) {
        return stockRepository.findByItemId(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock by item", itemId));
    }
}
