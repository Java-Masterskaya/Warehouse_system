package com.warehouse.service.reservation;

import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.repository.StockReserveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockAvailabilityService {
    private final StockReserveRepository reservationRepository;

    public int getAvailable(Stock stock) {
        long reserved = reservationRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE);

        return Math.toIntExact(stock.getQuantity() - reserved);
    }
}
