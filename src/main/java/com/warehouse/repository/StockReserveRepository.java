package com.warehouse.repository;

import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface StockReserveRepository extends JpaRepository<Reservation, Long> {

    @Query("""
                select coalesce(sum(r.quantity), 0)
                from Reservation r
                where r.stock = :stock
                and r.status = :status
            """)
    Integer findSumReserveByStockAndStatus(Stock stock, ReservationStatus status);

    @Modifying(clearAutomatically = true)
    @Query("""
                update Reservation r
                set r.status = com.warehouse.entity.ReservationStatus.EXPIRED
                where r.status = com.warehouse.entity.ReservationStatus.ACTIVE
                and r.expiredAt < :now
            """)
    int expireReservations(LocalDateTime now);
}
