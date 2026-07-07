package com.warehouse.repository;

import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockReserveRepository extends JpaRepository<Reservation, Long> {

    @Query("""
                select coalesce(sum(r.quantity), 0)
                from Reservation r
                where r.stock = :stock
                and r.status = :status
            """)
    Long findSumReserveByStockAndStatus(Stock stock, ReservationStatus status);
}
