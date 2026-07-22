package com.warehouse.repository;

import com.warehouse.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByItemId(Long itemId);

    @Query("select s.quantity from Stock s where s.item.id = :itemId")
    Optional<Integer> findQuantityByItemId(@Param("itemId") Long itemId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Stock s
            set s.quantity = s.quantity + :quantity,
                s.updatedAt = CURRENT_TIMESTAMP
            where s.item.id = :itemId
            """)
    int increaseQuantity(@Param("itemId") Long itemId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Stock s
            set s.quantity = s.quantity - :quantity,
                s.updatedAt = CURRENT_TIMESTAMP
            where s.item.id = :itemId and s.quantity >= :quantity
            """)
    int decreaseQuantityIfEnough(@Param("itemId") Long itemId, @Param("quantity") int quantity);

    // Обновление quantity напрямую (для FEFO списания)
    @Modifying(flushAutomatically = true)
    @Query("""
            update Stock s
            set s.quantity = :quantity,
                s.updatedAt = CURRENT_TIMESTAMP
            where s.item.id = :itemId
            """)
    int updateQuantity(@Param("itemId") Long itemId, @Param("quantity") int quantity);

    //Дополнительная операция не блокирующая операции чтения
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select s
    from Stock s
    where s.item.id = :itemId
        """)
    Optional<Stock> findByItemIdForUpdate(Long itemId);

    // Получает остаток без резервов с блокировкой для FEFO
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s.quantity - coalesce(
            (select sum(r.quantity) from Reservation r
             where r.stock = s and r.status = com.warehouse.entity.ReservationStatus.ACTIVE
             and r.expiredAt > :now), 0)
        from Stock s
        where s.item.id = :itemId
        """)
    Optional<Integer> findAvailableQuantityForUpdate(@Param("itemId") Long itemId, @Param("now") LocalDateTime now);

    void deleteByItemId(Long itemId);
}
