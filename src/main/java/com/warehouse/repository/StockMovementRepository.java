package com.warehouse.repository;

import com.warehouse.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для работы с движениями товаров на складе.
 * Предоставляет методы доступа к сущности {@link StockMovement}.
 */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Получить все движения товара (для внутреннего использования, без пагинации)
     */
    List<StockMovement> findByItemIdOrderByCreatedAtDesc(Long itemId);

    /**
     * Получить историю движений товара с пагинацией (для эндпоинта GET /api/v1/items/{itemId}/movements)
     * Сортировку передаем через Pageable
     * @param itemId ID товара
     * @param pageable параметры пагинации (page, size, sort)
     * @return страница с записями движений
     */
    Page<StockMovement> findByItemId(Long itemId, Pageable pageable);

    /**
     * Получить историю движений товара с фильтрацией по типу (с пагинацией)
     * Сортировку передаем через Pageable
     * @param itemId ID товара
     * @param type тип движения (RECEIVE или WRITE_OFF)
     * @param pageable параметры пагинации
     * @return страница с отфильтрованными записями
     */
    Page<StockMovement> findByItemIdAndType(
            Long itemId,
            MovementType type,
            Pageable pageable
    );
}