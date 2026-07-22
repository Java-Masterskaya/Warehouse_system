package com.warehouse.repository;

import com.warehouse.entity.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
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
    List<Batch> findNonExpiredByItemIdOrderByExpiryDateAsc(@Param("itemId") Long itemId, @Param("now") LocalDateTime now);

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

}
