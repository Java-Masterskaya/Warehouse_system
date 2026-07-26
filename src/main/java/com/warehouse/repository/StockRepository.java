package com.warehouse.repository;

import com.warehouse.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    @Query("""
            select s
            from Stock s
            where s.item.id = :itemId
              and s.warehouse.defaultWarehouse = true
            """)
    Optional<Stock> findByItemId(@Param("itemId") Long itemId);

    @Query("""
            select s.quantity
            from Stock s
            where s.item.id = :itemId
              and s.warehouse.defaultWarehouse = true
            """)
    Optional<Integer> findQuantityByItemId(@Param("itemId") Long itemId);

    Optional<Stock> findByItemIdAndWarehouseId(Long itemId, Long warehouseId);

    @Query("""
            select coalesce(sum(s.quantity), 0)
            from Stock s
            where s.item.id = :itemId
            """)
    Long findTotalQuantityByItemId(@Param("itemId") Long itemId);

    @Query("""
            select s
            from Stock s
            join fetch s.warehouse w
            where s.item.id = :itemId
            order by w.name, w.id
            """)
    List<Stock> findAllByItemIdWithWarehouse(@Param("itemId") Long itemId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update stock
            set quantity = quantity + :quantity,
                updated_at = current_timestamp
            where item_id = :itemId
              and warehouse_id = (select id from warehouses where is_default = true)
            """, nativeQuery = true)
    int increaseQuantity(@Param("itemId") Long itemId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update stock
            set quantity = quantity - :quantity,
                updated_at = current_timestamp
            where item_id = :itemId
              and warehouse_id = (select id from warehouses where is_default = true)
              and quantity >= :quantity
            """, nativeQuery = true)
    int decreaseQuantityIfEnough(@Param("itemId") Long itemId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Stock s
            set s.quantity = s.quantity - :quantity,
                s.updatedAt = CURRENT_TIMESTAMP
            where s.item.id = :itemId
              and s.warehouse.id = :warehouseId
              and s.quantity >= :quantity
            """)
    int decreaseQuantityIfEnoughAtWarehouse(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId,
            @Param("quantity") int quantity
    );

    //Дополнительная операция не блокирующая операции чтения
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s
            from Stock s
            where s.item.id = :itemId
              and s.warehouse.defaultWarehouse = true
        """)
    Optional<Stock> findByItemIdForUpdate(@Param("itemId") Long itemId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into stock (item_id, warehouse_id, quantity, updated_at, version)
            values (:itemId, :warehouseId, 0, current_timestamp, 0)
            on conflict (item_id, warehouse_id) do nothing
            """, nativeQuery = true)
    int createEmptyStockIfAbsent(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s
            from Stock s
            join fetch s.warehouse w
            where s.item.id = :itemId
              and w.id in :warehouseIds
            order by w.id
            """)
    List<Stock> findByItemAndWarehousesForUpdate(
            @Param("itemId") Long itemId,
            @Param("warehouseIds") List<Long> warehouseIds
    );

    void deleteByItemId(Long itemId);
}
