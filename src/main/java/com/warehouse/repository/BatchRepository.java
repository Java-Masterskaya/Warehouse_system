package com.warehouse.repository;

import com.warehouse.entity.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {

    /**
     * Найти все партии товара, отсортированные по возрастанию срока годности (FEFO).
     * Алиас для findByItemIdOrderByExpiryDateAsc для удобства.
     *
     * @param itemId ID товара
     * @return список партий, отсортированных по expiryDate
     */
    default List<Batch> findByItemIdOrderByExpiryDate(Long itemId) {
        return findByItemIdOrderByExpiryDateAsc(itemId);
    }

    /**
     * Найти все партии товара, отсортированные по возрастанию срока годности (FEFO).
     *
     * @param itemId ID товара
     * @return список партий, отсортированных по expiryDate
     */
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            ORDER BY b.expiryDate ASC
            """)
    List<Batch> findByItemIdOrderByExpiryDateAsc(@Param("itemId") Long itemId);

    /**
     * Найти неистекшие партии товара, отсортированные по возрастанию срока годности (FEFO).
     * Используется для списания - не используем просроченные партии.
     *
     * @param itemId ID товара
     * @param now текущее время
     * @return список неистекших партий с количеством > 0
     */
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.expiryDate > :now
            AND b.quantity > 0
            ORDER BY b.expiryDate ASC
            """)
    List<Batch> findNonExpiredByItemIdOrderByExpiryDateAsc(@Param("itemId") Long itemId,
                                                           @Param("now") LocalDateTime now);

    /**
     * Найти партию по ID (с подгрузкой item).
     *
     * @param id ID партии
     * @return опциональная партия
     */
    @Query("""
            SELECT b
            FROM Batch b
            JOIN FETCH b.item
            WHERE b.id = :id
            """)
    Optional<Batch> findWithItemById(@Param("id") Long id);

    /**
     * Найти все партии товара с подгрузкой item.
     *
     * @param itemId ID товара
     * @return список партий товара
     */
    @Query("""
            SELECT b
            FROM Batch b
            JOIN FETCH b.item
            WHERE b.item.id = :itemId
            ORDER BY b.expiryDate ASC
            """)
    List<Batch> findAllWithItemByItemId(@Param("itemId") Long itemId);

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
            WHERE b.expiryDate > :now
            AND b.expiryDate <= :maxDate
            AND b.quantity > 0
            ORDER BY b.expiryDate ASC
            """)
    List<Batch> findExpiringByDays(@Param("now") LocalDateTime now, @Param("maxDate") LocalDateTime maxDate);

    /**
     * Сумма количества непросроченных партий.
     * Используется для проверки доступного остатка без резерваций.
     *
     * @param itemId ID товара
     * @param now текущее время
     * @return сумма количества непросроченных партий или 0
     */
    @Query("""
            SELECT COALESCE(SUM(b.quantity), 0)
            FROM Batch b
            WHERE b.item.id = :itemId
            AND b.expiryDate > :now
            """)
    Optional<Integer> findNonExpiredSumByItemId(@Param("itemId") Long itemId, @Param("now") LocalDateTime now);

    /**
     * Очистить просроченные партии для конкретного товара.
     * Атомарное обновление всех просроченных партий в 0.
     * @param itemId ID товара
     * @param now текущее время
     * @return количество очищенных партий
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Batch b SET b.quantity = 0
            WHERE b.item.id = :itemId
            AND b.expiryDate < :now
            AND b.quantity > 0
            """)
    int clearExpiredBatchesByItemId(@Param("itemId") Long itemId, @Param("now") LocalDateTime now);

    /**
     * Найти все протухшие партии (expiryDate < now) с количеством > 0.
     * Используется для очистки просроченных партий.
     *
     * @param now текущее время
     * @return список протухших партий
     */
    @Query("""
            SELECT b
            FROM Batch b
            WHERE b.expiryDate < :now
            AND b.quantity > 0
            """)
    List<Batch> findExpiredWithQuantity(@Param("now") LocalDateTime now);

}
