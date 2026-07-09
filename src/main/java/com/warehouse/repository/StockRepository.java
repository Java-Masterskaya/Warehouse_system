package com.warehouse.repository;

import com.warehouse.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    //Дополнительная операция не блокирующая операции чтения
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select s
    from Stock s
    where s.item.id = :itemId
        """)
    Optional<Stock> findByItemIdForUpdate(Long itemId);

    void deleteByItemId(Long itemId);
}
