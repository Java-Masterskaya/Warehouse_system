package com.warehouse.repository;

import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockReserveRepository extends JpaRepository<Reservation, Long> {

    @Query("""
                select coalesce(sum(r.quantity), 0)
                from Reservation r
                where r.stock = :stock
                and r.status = :status
                and r.expiredAt > :now
            """)
    Integer findActiveReserveSumByStock(Stock stock, ReservationStatus status, LocalDateTime now);

    @Query("""
                select coalesce(sum(r.quantity), 0)
                from Reservation r
                where r.stock.item.id = :itemId
                and r.status = :status
                and r.expiredAt > :now
            """)
    Long findActiveReserveSumByItemId(
            @Param("itemId") Long itemId,
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime now
    );

    /**
     * Locks logically active reservations from newest to oldest.
     * This ordering lets cleanup cancel the newest whole reservations first and preserve FIFO allocation.
     *
     * @param stock locked stock scope
     * @param now cleanup timestamp
     * @return active, non-expired reservations ordered newest first
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                select r
                from Reservation r
                where r.stock = :stock
                and r.status = com.warehouse.entity.ReservationStatus.ACTIVE
                and r.expiredAt > :now
                order by r.createdAt desc, r.id desc
            """)
    List<Reservation> findActiveByStockForUpdate(
            @Param("stock") Stock stock,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("""
                update Reservation r
                set r.status = com.warehouse.entity.ReservationStatus.EXPIRED
                where r.status = com.warehouse.entity.ReservationStatus.ACTIVE
                and r.expiredAt < :now
            """)
    int expireReservations(LocalDateTime now);
}
