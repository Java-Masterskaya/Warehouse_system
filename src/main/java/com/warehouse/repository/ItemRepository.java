package com.warehouse.repository;

import com.warehouse.dto.response.item.ItemDetailsProjection;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.entity.Item;
import com.warehouse.repository.projection.LowStockProjection;
import org.springframework.data.domain.Pageable;
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

    boolean existsByBarcode(String barcode);

    @Query("""
            SELECT DISTINCT i.category
            FROM Item i
            WHERE i.active = true
            """)
    List<String> findDistinctCategories();

    @Query("""
            SELECT new com.warehouse.dto.response.item.ItemDetailsProjection(
                i.id,
                i.sku,
                i.name,
                i.category,
                i.minStock,
                COALESCE(SUM(s.quantity), 0),
                i.price,
                i.cost,
                i.active,
                i.createdAt,
                i.updatedAt,
                i.barcode
            )
            FROM Item i
            LEFT JOIN Stock s ON s.item.id = i.id
            WHERE i.id = :itemId
            GROUP BY i.id, i.sku, i.name, i.category, i.minStock,
                i.price, i.cost, i.active, i.createdAt, i.updatedAt
            """)
    Optional<ItemDetailsProjection> findWithStock(@Param("itemId") Long itemId);

    @Query("""
        SELECT
            i.id as id,
            i.sku as sku,
            i.name as name,
            i.category as category,
            COALESCE(SUM(s.quantity), 0) as currentStock,
            i.minStock as minStock
        FROM Item i
        LEFT JOIN Stock s ON s.item.id = i.id
        WHERE i.active = true
        GROUP BY i.id, i.sku, i.name, i.category, i.minStock
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

    /**
     * Найти товары с NULL barcode, отсортированные по id для стабильной пагинации.
     * Используется батчевой backfill-джобой.
     *
     * @param lastProcessedId id последнего обработанного товара (не включая)
     * @param pageable        пагинация, определяющая размер батча
     * @return список товаров, требующих backfill
     */
    @Query("SELECT i FROM Item i WHERE i.barcode IS NULL AND i.id > :lastId ORDER BY i.id ASC")
    List<Item> findByBarcodeIsNullAndIdGreaterThanOrderByIdAsc(
            @Param("lastId") Long lastProcessedId,
            Pageable pageable
    );

    /**
     * Быстро проверить, остались ли ещё NULL barcode.
     *
     * @return true, если хотя бы одна строка всё ещё имеет NULL barcode
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM items WHERE barcode IS NULL)", nativeQuery = true)
    boolean existsByBarcodeIsNull();
}
