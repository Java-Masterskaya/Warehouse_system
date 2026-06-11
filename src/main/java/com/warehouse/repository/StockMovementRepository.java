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
}