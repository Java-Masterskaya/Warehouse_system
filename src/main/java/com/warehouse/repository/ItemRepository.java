package com.warehouse.repository;

import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.entity.Item;
import com.warehouse.repository.projection.LowStockProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    boolean existsBySku(String sku);

    Optional<Item> findBySku(String sku);

    @Query("""
            SELECT DISTINCT i.category
            FROM Item i
            WHERE i.active = true
            """)
    List<String> findDistinctCategories();

    @Query("""
            SELECT new com.warehouse.dto.response.item.ItemDetailsResponse(
                i.id,
                i.sku,
                i.name,
                i.category,
                i.minStock,
                s.quantity,
                i.price,
                i.cost,
                i.active,
                i.createdAt,
                i.updatedAt
            )
            FROM Item i
            JOIN Stock s on s.item.id = i.id
            WHERE i.id = :itemId
            """)
    Optional<ItemDetailsResponse> findWithStock(@Param("itemId") Long itemId);

    @Query("""
        SELECT
            i.id as id,
            i.sku as sku,
            i.name as name,
            i.category as category,
            s.quantity as currentStock,
            i.minStock as minStock
        FROM Item i
        JOIN Stock s ON s.item.id = i.id
        WHERE s.quantity < i.minStock AND i.active = true
        ORDER BY (i.minStock - s.quantity) DESC
        """)
    List<LowStockProjection> findLowStockItems();

    /**
     * Подсчитывает количество активных товаров.
     *
     * @return количество товаров с is_active = true
     */
    long countByActiveTrue();

    /**
     * Суммарная стоимость запасов: Σ quantity × cost.
     * COALESCE защищает от NULL (старые товары без cost, товары без stock).
     */
    @Query("""
            SELECT COALESCE(SUM(COALESCE(s.quantity, 0) * COALESCE(i.cost, 0)), 0)
            FROM Item i
            LEFT JOIN Stock s ON s.item = i
            WHERE i.active = true
            """)
    BigDecimal calculateTotalStockValuation();

    /**
     * Разрез по категориям.
     */
    @Query("""
            SELECT new com.warehouse.dto.response.valuation.CategoryValuation(
                i.category,
                COALESCE(SUM(COALESCE(s.quantity, 0) * COALESCE(i.cost, 0)), 0)
            )
            FROM Item i
            LEFT JOIN Stock s ON s.item = i
            WHERE i.active = true
            GROUP BY i.category
            ORDER BY i.category
            """)
    List<CategoryValuation> calculateValuationByCategory();
}

