package com.warehouse.repository;

import com.warehouse.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * @param itemId    ID товара
     * @param createdAt время создания алерта (из события)
     * @return true, если алерт уже существует
     */
    boolean existsByItemIdAndCreatedAt(Long itemId, LocalDateTime createdAt);

    /**
     * Находит алерты по itemId.
     *
     * @param itemId ID товара
     * @return список алертов для товара
     */
    List<StockAlert> findByItemId(Long itemId);

    /**
     * Атомарная вставка с игнорированием дубликатов.
     * Возвращает 1 если вставлено, 0 если дубликат.
     *
     * Уникальный индекс: (item_id, triggered_at)
     *
     * @param itemId      ID товара
     * @param currentStock текущий остаток
     * @param minStock минимальный порог
     * @param triggeredBy источник алерта
     * @param triggeredAt время срабатывания
     * @return 1 если вставлено, 0 если дубликат
     */
    @Modifying
    @Query(value = """
            INSERT INTO stock_alerts 
                (item_id, current_stock, min_stock, triggered_by, triggered_at, created_at)
            VALUES 
                (:itemId, :currentStock, :minStock, :triggeredBy, :triggeredAt, NOW())
            ON CONFLICT (item_id, triggered_at) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(
            @Param("itemId") Long itemId,
            @Param("currentStock") int currentStock,
            @Param("minStock") int minStock,
            @Param("triggeredBy") String triggeredBy,
            @Param("triggeredAt") LocalDateTime triggeredAt
    );
}