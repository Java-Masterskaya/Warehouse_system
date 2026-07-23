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

    // Синхронизация stock.quantity = SUM(batch.quantity)
    // Обновляет stock.quantity на сумму количества всех активных партий
    @Modifying(flushAutomatically = true)
    @Query("""
            update Stock s
            set s.quantity = (
                select coalesce(sum(b.quantity), 0)
                from Batch b
                where b.item.id = :itemId
            ),
            s.updatedAt = CURRENT_TIMESTAMP
            where s.item.id = :itemId
            """)
    int syncQuantityWithBatches(@Param("itemId") Long itemId);

    //Дополнительная операция не блокирующая операции чтения
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select s
    from Stock s
    where s.item.id = :itemId
        """)
    Optional<Stock> findByItemIdForUpdate(Long itemId);

    // Получает остаток без резервов с блокировкой для FEFO
    // ВАЖНО: Возвращаем SUM(batches.quantity), а не stock.quantity!
    // stock.quantity может не совпадать с суммой партий (это агрегатное поле)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select coalesce(sum(b.quantity), 0)
        from Stock s
        join s.item i
        left join Batch b on b.item.id = i.id and b.expiryDate > :now
        where s.item.id = :itemId
        """)
    Optional<Integer> findAvailableQuantityFromBatchesForUpdate(@Param("itemId") Long itemId,
                                                                @Param("now") LocalDateTime now);

    void deleteByItemId(Long itemId);
}
