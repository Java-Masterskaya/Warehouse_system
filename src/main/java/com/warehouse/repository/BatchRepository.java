package com.warehouse.repository;

import com.warehouse.entity.Batch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {

    /**
     * Найти партии товара на конкретном складе в порядке FEFO.
     *
     * @param itemId ID товара
     * @param warehouseId ID склада
     * @return партии в порядке FEFO
     */
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.warehouse.id = :warehouseId
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<Batch> findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.warehouse.id = :warehouseId
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<Batch> findByItemAndWarehouseOrderByExpiryDateAscForUpdate(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId
    );

    /**
     * Найти неистекшие партии товара с блокировкой для обновления.
     * Используется для списания с гарантией атомарности (PESSIMISTIC_WRITE).
     *
     * @param itemId ID товара
     * @param warehouseId ID склада
     * @param now текущее время
     * @return список неистекших партий с количеством > 0
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.warehouse.id = :warehouseId
            AND b.expiryDate > :now
            AND b.quantity > 0
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<Batch> findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId,
            @Param("now") LocalDateTime now
    );

    /**
     * Найти партии, срок годности которых истекает в ближайшие N дней.
     * FEFO: сортировка по expiryDate ASC (ближайшие первыми).
     * Исключает просроченные и пустые партии.
     *
     * @param now текущее время
     * @param maxDate максимальная дата (now + days)
     * @return список партий с количеством > 0
     */
    @Query("""
            SELECT b
            FROM Batch b
            JOIN FETCH b.item i
            JOIN FETCH i.category
            JOIN FETCH b.warehouse
            WHERE b.expiryDate > :now
            AND b.expiryDate <= :maxDate
            AND b.quantity > 0
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<Batch> findExpiringByDays(@Param("now") LocalDateTime now, @Param("maxDate") LocalDateTime maxDate);

    /**
     * Сумма количества непросроченных партий.
     * Используется для проверки доступного остатка без резерваций.
     *
     * @param itemId ID товара
     * @param warehouseId ID склада
     * @param now текущее время
     * @return сумма количества непросроченных партий или 0
     */
    @Query("""
            SELECT COALESCE(SUM(b.quantity), 0)
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.warehouse.id = :warehouseId
            AND b.expiryDate > :now
            """)
    long findNonExpiredSumByItemAndWarehouse(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT DISTINCT
                b.item.id AS itemId,
                b.warehouse.id AS warehouseId
            FROM Batch b
            WHERE b.expiryDate <= :now
            AND b.quantity > 0
            """)
    List<ExpiredBatchScope> findExpiredScopesWithQuantity(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.warehouse.id = :warehouseId
            AND b.expiryDate <= :now
            AND b.quantity > 0
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<Batch> findExpiredByItemAndWarehouseForUpdate(
            @Param("itemId") Long itemId,
            @Param("warehouseId") Long warehouseId,
            @Param("now") LocalDateTime now
    );

}
