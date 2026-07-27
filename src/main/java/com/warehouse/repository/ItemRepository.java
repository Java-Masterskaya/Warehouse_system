package com.warehouse.repository;

import com.warehouse.dto.response.item.ItemDetailsProjection;
import com.warehouse.dto.response.item.ItemExportDto;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.entity.Item;
import com.warehouse.repository.projection.LowStockProjection;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    boolean existsBySku(String sku);

    @Override
    @EntityGraph(attributePaths = "category")
    Page<Item> findAll(Specification<Item> spec, Pageable pageable);

    Optional<Item> findBySku(String sku);

    @Query("""
            SELECT new com.warehouse.dto.response.item.ItemDetailsProjection(
                i.id,
                i.sku,
                i.name,
                i.category.name,
                i.minStock,
                COALESCE(SUM(s.quantity), 0),
                i.price,
                i.cost,
                i.active,
                i.createdAt,
                i.updatedAt
            )
            FROM Item i
            LEFT JOIN Stock s ON s.item.id = i.id
            WHERE i.id = :itemId
            GROUP BY i.id, i.sku, i.name, i.category.name, i.minStock,
                i.price, i.cost, i.active, i.createdAt, i.updatedAt
            """)
    Optional<ItemDetailsProjection> findWithStock(@Param("itemId") Long itemId);

    @Query("""
        SELECT
            i.id as id,
            i.sku as sku,
            i.name as name,
            i.category.name as category,
            COALESCE(SUM(s.quantity), 0) as currentStock,
            i.minStock as minStock
        FROM Item i
        LEFT JOIN Stock s ON s.item.id = i.id
        WHERE i.active = true
        GROUP BY i.id, i.sku, i.name, i.category.name, i.minStock
        HAVING COALESCE(SUM(s.quantity), 0) < i.minStock
        ORDER BY (i.minStock - COALESCE(SUM(s.quantity), 0)) DESC
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
     *
     * @return суммарная стоимость всех активных товаров
     */
    @Query("""
            SELECT COALESCE(SUM(COALESCE(s.quantity, 0) * COALESCE(i.cost, 0)), 0)
            FROM Item i
            LEFT JOIN Stock s ON s.item = i
            WHERE i.active = true
            """)
    BigDecimal calculateTotalStockValuation();

    /**
     * Разрез стоимости по категориям.
     *
     * @return список CategoryValuation с суммами по каждой категории
     */
    @Query("""
            SELECT new com.warehouse.dto.response.valuation.CategoryValuation(
                i.category.name,
                COALESCE(SUM(COALESCE(s.quantity, 0) * COALESCE(i.cost, 0)), 0)
            )
            FROM Item i
            LEFT JOIN Stock s ON s.item = i
            WHERE i.active = true
            GROUP BY i.category.name
            ORDER BY i.category.name
            """)
    List<CategoryValuation> calculateValuationByCategory();

    boolean existsByCategoryId(Long categoryId);

    @QueryHints(value = @QueryHint(name = org.hibernate.annotations.QueryHints.FETCH_SIZE, value = "500"))
    @Query("""
                select new com.warehouse.dto.response.item.ItemExportDto(
                    i.sku,
                    i.name,
                    i.category.name,
                    coalesce(sum(s.quantity), 0L),
                    i.price
                )
                from Item i
                left join Stock s on s.item = i
                group by i.id, i.sku, i.name, i.category, i.price
            """)
    Stream<ItemExportDto> streamAllForExport();

    @Query("select i.sku from Item i where i.sku in :skus")
    List<String> findAllSkusIn(@Param("skus") Set<String> skus);
}
