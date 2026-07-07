package com.warehouse.repository;

import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockReserveRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findAllByStockAndStatus(Stock stock, ReservationStatus status);
}
