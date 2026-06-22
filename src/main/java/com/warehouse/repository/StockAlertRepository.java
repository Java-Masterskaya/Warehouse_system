package com.warehouse.repository;

import com.warehouse.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для работы с записями о низком остатке.
 */
@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
}
