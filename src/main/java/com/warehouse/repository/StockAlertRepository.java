package com.warehouse.repository;

import com.warehouse.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Репозиторий для работы с записями о низком остатке.
 */
@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {

    /**
     * Проверяет, существует ли алерт для данного itemId и createdAt.
     * Используется для idempotent consumer.
     *
     * @param itemId       ID товара
     * @param createdAt    время создания алерта (из события)
     * @return true, если алерт уже существует
     */
    boolean existsByItemIdAndCreatedAt(Long itemId, LocalDateTime createdAt);

    /**
     * Находит алерты по itemId.
     *
     * @param itemId ID товара
     * @return список алертов
     */
    List<StockAlert> findByItemId(Long itemId);
}
