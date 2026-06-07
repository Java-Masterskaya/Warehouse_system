package com.warehouse.repository;

import com.warehouse.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByItemId(Long itemId);

    @Query("SELECT s FROM Stock s JOIN FETCH s.item WHERE s.item.id = :itemId")
    Optional<Stock> findWithItemById(Long itemId);
}
