package com.warehouse.repository;

import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Возвращает историю движений товара с возможностью фильтрации
     * по типу движения и постраничного вывода результатов.
     *
     * @param itemId   идентификатор товара
     * @param type     тип движения для фильтрации, может быть {@code null}
     * @param pageable параметры пагинации
     * @return страница с историей движений товара
     */
    @Query("""
                select new com.warehouse.dto.response.movement.StockMovementHistoryResponse(
                    sm.id,
                    sm.type,
                    sm.quantity,
                    u.username,
                    sm.createdAt
                )
                from StockMovement sm
                join sm.user u
                where sm.item.id = :itemId
                  and (:type is null or sm.type = :type)
                order by sm.createdAt desc
            """)
    Page<StockMovementHistoryResponse> findHistoryByItemId(
            @Param("itemId") Long itemId,
            @Param("type") MovementType type,
            Pageable pageable
    );
}